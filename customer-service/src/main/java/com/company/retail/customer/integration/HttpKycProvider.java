package com.company.retail.customer.integration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * eKYC HTTP 适配器（P-12；W-SIM2）：按提议协议 v1 信封
 * POST {provider-url}/kyc/v1/verifications。报文只含 verificationId / 脱敏号 /
 * 材料类型引用（红线 B-7），任何失败返回 null（核验流程不因通知失败中断）。
 */
public class HttpKycProvider implements KycProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public HttpKycProvider(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    @Override
    public KycSubmission submit(long verificationId, String maskedNumber, String documentType) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("verificationId", verificationId);
            payload.put("maskedNumber", maskedNumber == null ? "" : maskedNumber);
            payload.put("documentType", documentType == null ? "" : documentType);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("protocolVersion", "1.0");
            envelope.put("messageId", UUID.randomUUID().toString());
            envelope.put("timestamp", java.time.OffsetDateTime.now().toString());
            envelope.put("payload", payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/kyc/v1/verifications"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(envelope)))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            return new KycSubmission(extract(resp.body(), "providerRef"),
                    extract(resp.body(), "status"), "EKYC-HTTP");
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static String extract(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }
}
