package com.company.retail.underwriting.integration;

import com.company.retail.common.outbound.IdempotencyKeys;
import com.company.retail.common.outbound.OutboundHttpClient;
import com.company.retail.common.outbound.OutboundHttpClientFactory;
import com.company.retail.common.outbound.OutboundProfile;
import com.company.retail.common.outbound.OutboundResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DMS 集成商出站适配器（I-02 审查结果通知，R07 §4.4；W-SIM2 六向防腐层之一）。
 *
 * <p>按提议协议 v1 信封 POST {outbound-url}/dms/v1/underwriting-result。
 * 双模装配：{@code retail.dms.outbound-url} 空 = 挡板（原语义：结果只落 outbox，
 * 人工导出），非空 = 事件消费时真 HTTP 推送，失败落 {@code integration.outbound_tasks}
 * 留痕（表 V9 已备，人工补发路径）。</p>
 *
 * <p>P0-7：走 ADVISORY 档——单次尝试；同一 case + decision 的重发共享稳定幂等键，
 * 调度器重试不会在对端产生重复业务。</p>
 */
public class HttpDmsOutboundAdapter implements DmsResultSender {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHANNEL = "dms-result";
    private static final String PATH = "/dms/v1/underwriting-result";

    private static final Pattern DMS_REF =
            Pattern.compile("\"dmsRef\"\\s*:\\s*\"([^\"]+)\"");

    private final String baseUrl;
    private final OutboundHttpClient client;

    /** 挡板/单测兼容：使用默认策略。 */
    public HttpDmsOutboundAdapter(String baseUrl) {
        this(baseUrl, OutboundHttpClientFactory.defaults().client(CHANNEL, OutboundProfile.ADVISORY));
    }

    public HttpDmsOutboundAdapter(String baseUrl, OutboundHttpClient client) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.client = client;
    }

    @Override
    public String sendResult(long caseId, long applicationId, String decision,
                             String reasonCode, String traceId) {
        try {
            String idempotencyKey = IdempotencyKeys.of("DMS-UW-RESULT", caseId, decision);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("caseId", caseId);
            payload.put("applicationId", applicationId);
            payload.put("decision", decision);
            if (reasonCode != null && !reasonCode.isBlank()) {
                payload.put("reasonCode", reasonCode);
            }
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("protocolVersion", "1.0");
            envelope.put("messageId", IdempotencyKeys.messageId(idempotencyKey).toString());
            envelope.put("idempotencyKey", idempotencyKey);
            envelope.put("timestamp", OffsetDateTime.now().toString());
            envelope.put("payload", payload);

            OutboundResult response = client.postJson(baseUrl + PATH,
                    MAPPER.writeValueAsString(envelope),
                    Map.of("Idempotency-Key", idempotencyKey));
            if (response.getStatus() != 200) {
                return null;
            }
            Matcher matcher = DMS_REF.matcher(response.getBody());
            return matcher.find() ? matcher.group(1) : "DMS-UNREF";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }
}
