package com.company.retail.customer.integration;

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
 * eKYC HTTP 适配器（P-12；W-SIM2）：按提议协议 v1 信封
 * POST {provider-url}/kyc/v1/verifications。报文只含 verificationId / 脱敏号 /
 * 材料类型引用（红线 B-7），任何失败返回 null（核验流程不因通知失败中断）。
 *
 * <p>P0-7：走 ADVISORY 档——单次尝试、失败不阻断主流程；同一核验单的重发共享稳定幂等键。</p>
 */
public class HttpKycProvider implements KycProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHANNEL = "kyc";
    private static final String PATH = "/kyc/v1/verifications";

    private final String baseUrl;
    private final OutboundHttpClient client;

    /** 挡板/单测兼容：使用默认策略。 */
    public HttpKycProvider(String baseUrl) {
        this(baseUrl, OutboundHttpClientFactory.defaults().client(CHANNEL, OutboundProfile.ADVISORY));
    }

    public HttpKycProvider(String baseUrl, OutboundHttpClient client) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.client = client;
    }

    @Override
    public KycSubmission submit(long verificationId, String maskedNumber, String documentType) {
        try {
            String idempotencyKey = IdempotencyKeys.of("KYC", verificationId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("verificationId", verificationId);
            payload.put("maskedNumber", maskedNumber == null ? "" : maskedNumber);
            payload.put("documentType", documentType == null ? "" : documentType);
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
            return new KycSubmission(extract(response.getBody(), "providerRef"),
                    extract(response.getBody(), "status"), "EKYC-HTTP");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static String extract(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }
}
