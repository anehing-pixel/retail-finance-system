package com.company.retail.bureau.acl;

import com.company.retail.bureau.domain.CreditBureau;
import com.company.retail.common.outbound.IdempotencyKeys;
import com.company.retail.common.outbound.OutboundHttpClient;
import com.company.retail.common.outbound.OutboundHttpClientFactory;
import com.company.retail.common.outbound.OutboundProfile;
import com.company.retail.common.outbound.OutboundResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CIC HTTP 适配器（W-SIM：外部机构模拟器 / 未来真实专线接入点）。
 *
 * <p>按我方提议协议 v1（R07 §4.1 通用报文规范 + §4.7 查询 / §4.8 报送 / §4.9 异议）
 * 与对端通信：信封 {@code protocolVersion/messageId/idempotencyKey/timestamp/payload}，
 * 响应 {@code code=OK + data} 或错误体。对端由 {@code retail.bureau.cic-url}
 * （env {@code RETAIL_BUREAU_CIC_URL}）配置；双模装配见 BureauServiceConfig——
 * 不配 URL 走 {@link CicStubAdapter} 挡板，配了走本实现。</p>
 *
 * <p>P0-7：查询走 QUERY 档（可配重试）；报送/异议走 <b>强制不重试</b> 的 WRITE 档。
 * 同一业务对象的重发共享稳定幂等键（不再每次随机），对端可按 messageId 去重。</p>
 *
 * <p>故障语义：网络异常 / 非 2xx / 响应解析失败一律译成 {@code InquiryOutcome.failed}
 * / {@code ReportOutcome.rejected} / DisputeOutcome(false)，由用例层重试（3 次）后
 * 转人工通道——ACL 不吞错、不留假 COMPLETED（ADR-RF-005）。</p>
 */
public class HttpCicAdapter implements BureauAdapter {

    /** 报文留痕标记（区别于挡板 STUB，用于验收断言"确实走了 HTTP 对端"）。 */
    public static final String HTTP_MARKER = "HTTP-SIM";

    private static final String PROTOCOL_VERSION = "1.0";

    private final String baseUrl;
    private final OutboundHttpClient inquiryClient;
    private final OutboundHttpClient writeClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 挡板/单测兼容：使用默认策略。 */
    public HttpCicAdapter(String baseUrl) {
        this(baseUrl, OutboundHttpClientFactory.defaults());
    }

    public HttpCicAdapter(String baseUrl, OutboundHttpClientFactory outbound) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("cic-url 为空：HTTP 适配器需要明确对端地址");
        }
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        this.inquiryClient = outbound.client("cic-inquiry", OutboundProfile.QUERY);
        this.writeClient = outbound.noRetryClient("cic-write", OutboundProfile.WRITE);
    }

    @Override
    public CreditBureau bureau() {
        return CreditBureau.CIC;
    }

    @Override
    public InquiryOutcome inquire(BureauInquiryCommand command) {
        if (command == null || command.consentId() <= 0) {
            // INV-5 双兜底：适配器层面再挡一次无同意查询
            return InquiryOutcome.failed("{\"http\":\"CIC\",\"error\":\"CONSENT_ID_REQUIRED\"}");
        }
        String key = IdempotencyKeys.of("CIC-INQ", command.caseId(), command.consentId(),
                command.bureau(), command.purpose());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", command.caseId());
        payload.put("consentId", command.consentId());
        payload.put("purpose", command.purpose());
        JsonNode data = post("/cic/v1/inquiries", payload, key, inquiryClient);
        if (data == null) {
            return InquiryOutcome.failed("{\"http\":\"CIC\",\"error\":\"INQUIRY_CALL_FAILED\"}");
        }
        return InquiryOutcome.completed(
                data.path("inquiryRef").asText(""),
                data.toString(),
                data.path("costAmount").asLong(0));
    }

    @Override
    public ReportOutcome submitReport(BureauReportCommand command) {
        String key = IdempotencyKeys.of("CIC-RPT", command.loanId(), command.trigger(), command.bizDate());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", command.loanId());
        payload.put("consentId", command.consentId());
        payload.put("trigger", command.trigger());
        payload.put("bizDate", command.bizDate());
        JsonNode data = post("/cic/v1/reports", payload, key, writeClient);
        if (data == null) {
            return ReportOutcome.rejected("{\"http\":\"CIC\",\"error\":\"REPORT_CALL_FAILED\"}");
        }
        return ReportOutcome.sent(data.path("reportRef").asText(""), data.toString());
    }

    @Override
    public DisputeOutcome fileDispute(BureauDisputeCommand command) {
        String key = IdempotencyKeys.of("CIC-DSP", command.disputeId(), command.bureau());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("disputeId", command.disputeId());
        JsonNode data = post("/cic/v1/disputes", payload, key, writeClient);
        if (data == null) {
            return new DisputeOutcome(false, "", "{\"http\":\"CIC\",\"error\":\"DISPUTE_CALL_FAILED\"}");
        }
        return new DisputeOutcome(data.path("accepted").asBoolean(false),
                data.path("disputeRef").asText(""), data.toString());
    }

    /** 提议协议 v1 信封 POST；失败返回 null（不抛出，故障语义收敛在 Outcome）。 */
    private JsonNode post(String path, Map<String, Object> payload, String idempotencyKey,
                          OutboundHttpClient client) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("protocolVersion", PROTOCOL_VERSION);
            envelope.put("messageId", IdempotencyKeys.messageId(idempotencyKey).toString());
            envelope.put("idempotencyKey", idempotencyKey);
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("payload", payload);
            OutboundResult response = client.postJson(baseUrl + path,
                    mapper.writeValueAsString(envelope),
                    Map.of("Idempotency-Key", idempotencyKey));
            if (!response.is2xx()) {
                return null;
            }
            JsonNode root = mapper.readTree(response.getBody());
            if (!"OK".equals(root.path("code").asText())) {
                return null;
            }
            return root.path("data");
        } catch (Exception e) {
            return null;
        }
    }
}
