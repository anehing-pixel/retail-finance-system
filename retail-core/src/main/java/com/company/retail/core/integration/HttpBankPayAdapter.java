package com.company.retail.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 银行代付 HTTP 适配器（P-09；W-SIM2）：按提议协议 v1 信封
 * POST {bank-pay-url}/bank/v1/disbursements，同步受理返回 PAID + bankRef。
 *
 * <p>失败语义：HTTP 异常/非 200/银行拒绝 → paid=false（由事务执行器统一回滚，
 * 不产生半代付状态）。金额 BIGINT 整数日元、账户只传脱敏引用（红线 B-7）。</p>
 */
public class HttpBankPayAdapter implements BankPayAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public HttpBankPayAdapter(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    @Override
    public PayResult pay(String instructionRef, long amount, String payeeAccountRef) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("instructionRef", instructionRef);
            payload.put("amount", amount);
            payload.put("payeeAccountRef", payeeAccountRef == null ? "" : payeeAccountRef);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("protocolVersion", "1.0");
            envelope.put("messageId", UUID.randomUUID().toString());
            envelope.put("timestamp", java.time.OffsetDateTime.now().toString());
            envelope.put("payload", payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/bank/v1/disbursements"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(envelope)))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new PayResult(false, "", "HTTP_" + resp.statusCode() + ":" + resp.body());
            }
            String bankRef = extract(resp.body(), "bankRef");
            String status = extract(resp.body(), "status");
            if (!"PAID".equals(status)) {
                return new PayResult(false, "", "BANK_STATUS_" + status);
            }
            return PayResult.paid(bankRef.isEmpty() ? "BANK-UNREF" : bankRef);
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new PayResult(false, "", "BANK_UNREACHABLE:" + e.getMessage());
        }
    }

    /** 轻量取值（响应形状固定，避免在本层引 JSON 树遍历）。 */
    private static String extract(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }
}
