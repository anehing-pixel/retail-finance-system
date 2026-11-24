package com.company.retail.core.integration;

import com.company.retail.common.outbound.IdempotencyKeys;
import com.company.retail.common.outbound.OutboundHttpClient;
import com.company.retail.common.outbound.OutboundHttpClientFactory;
import com.company.retail.common.outbound.OutboundProfile;
import com.company.retail.common.outbound.OutboundResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 银行代付 HTTP 适配器（P-09；W-SIM2）：按提议协议 v1 信封
 * POST {bank-pay-url}/bank/v1/disbursements，同步受理返回 PAID + bankRef。
 *
 * <p>P0-7：走共享 {@link OutboundHttpClient} 的 FINANCIAL 档——
 * <b>资金动作绝不自动重试</b>，超时/熔断/非 200 一律 paid=false，由事务执行器回滚。</p>
 *
 * <p>失败语义：HTTP 异常/非 200/银行拒绝 → paid=false（由事务执行器统一回滚，
 * 不产生半代付状态）。金额 BIGINT 整数日元、账户只传脱敏引用（红线 B-7）。</p>
 */
public class HttpBankPayAdapter implements BankPayAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHANNEL = "bank-pay";
    private static final String PATH = "/bank/v1/disbursements";

    private final String baseUrl;
    private final OutboundHttpClient client;

    /** 挡板/单测兼容：使用默认策略（不放宽）。 */
    public HttpBankPayAdapter(String baseUrl) {
        this(baseUrl, OutboundHttpClientFactory.defaults().client(CHANNEL, OutboundProfile.FINANCIAL));
    }

    public HttpBankPayAdapter(String baseUrl, OutboundHttpClient client) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.client = client;
    }

    @Override
    public PayResult pay(String instructionRef, long amount, String payeeAccountRef) {
        try {
            String idempotencyKey = IdempotencyKeys.of("BANK-PAY", instructionRef);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("instructionRef", instructionRef);
            payload.put("amount", amount);
            payload.put("payeeAccountRef", payeeAccountRef == null ? "" : payeeAccountRef);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("protocolVersion", "1.0");
            envelope.put("messageId", IdempotencyKeys.messageId(idempotencyKey).toString());
            envelope.put("idempotencyKey", idempotencyKey);
            envelope.put("timestamp", OffsetDateTime.now().toString());
            envelope.put("payload", payload);

            OutboundResult resp = client.postJson(baseUrl + PATH, MAPPER.writeValueAsString(envelope),
                    Map.of("Idempotency-Key", idempotencyKey));
            if (resp.getStatus() != 200) {
                return new PayResult(false, "", resp.getStatus() < 0
                        ? "BANK_UNREACHABLE:" + resp.getErrorKind()
                        : "HTTP_" + resp.getStatus() + ":" + resp.getBody());
            }
            String bankRef = extract(resp.getBody(), "bankRef");
            String status = extract(resp.getBody(), "status");
            if (!"PAID".equals(status)) {
                return new PayResult(false, "", "BANK_STATUS_" + status);
            }
            return PayResult.paid(bankRef.isEmpty() ? "BANK-UNREF" : bankRef);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return new PayResult(false, "", "BANK_SERIALIZE_ERROR");
        }
    }

    /** 轻量取值（响应形状固定，避免在本层引 JSON 树遍历）。 */
    private static String extract(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }
}
