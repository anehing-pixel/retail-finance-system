package com.company.retail.underwriting.integration;

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
 * DMS 集成商出站适配器（I-02 审查结果通知，R07 §4.4；W-SIM2 六向防腐层之一）。
 *
 * <p>按提议协议 v1 信封 POST {outbound-url}/dms/v1/underwriting-result。
 * 双模装配：{@code retail.dms.outbound-url} 空 = 挡板（原语义：结果只落 outbox，
 * 人工导出），非空 = 事件消费时真 HTTP 推送，失败落 {@code integration.outbound_tasks}
 * 留痕（表 V9 已备，人工补发路径）。</p>
 */
public class HttpDmsOutboundAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public HttpDmsOutboundAdapter(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    /**
     * 推送审查决定。
     *
     * @return DMS 受理引用（accepted）；null=推送失败（调用方落 outbound_tasks）
     */
    public String sendResult(long caseId, long applicationId, String decision,
                             String reasonCode, String traceId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("caseId", caseId);
            payload.put("applicationId", applicationId);
            payload.put("decision", decision);
            if (reasonCode != null && !reasonCode.isBlank()) {
                payload.put("reasonCode", reasonCode);
            }
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("protocolVersion", "1.0");
            envelope.put("messageId", UUID.randomUUID().toString());
            envelope.put("timestamp", java.time.OffsetDateTime.now().toString());
            envelope.put("payload", payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/dms/v1/underwriting-result"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(envelope)))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"dmsRef\"\\s*:\\s*\"([^\"]+)\"").matcher(resp.body());
            return m.find() ? m.group(1) : "DMS-UNREF";
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
