package com.company.retail.contract.integration;

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
 * 法定书面文件送达通道适配器（P-14，R07 §4.15；W-SIM2 六向防腐层之一）。
 *
 * <p>按提议协议 v1 信封 POST {delivery-url}/notify/v1/deliveries，受理返回回执引用。
 * 双模装配：{@code retail.contract.delivery-url} 空 = 挡板（原语义：纯 DB 交付留痕，
 * C-08），非空 = 留痕同时向送达通道推送受理。送达通知失败<b>不中断</b>交付留痕主流程
 * （通知是咨询性副本，权威留痕在 statutory_documents）。</p>
 */
public class HttpDeliveryChannelAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public HttpDeliveryChannelAdapter(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    /** 送达受理推送；返回回执引用，失败返回 null（调用方只记日志，不抛）。 */
    public String notifyDelivered(long contractId, String docType, String deliveryMethod, String traceId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contractId", contractId);
            payload.put("docType", docType);
            payload.put("deliveryMethod", deliveryMethod);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("protocolVersion", "1.0");
            envelope.put("messageId", UUID.randomUUID().toString());
            envelope.put("timestamp", java.time.OffsetDateTime.now().toString());
            envelope.put("payload", payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/notify/v1/deliveries"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(envelope)))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            Matcher m = Pattern.compile("\"receiptRef\"\\s*:\\s*\"([^\"]+)\"").matcher(resp.body());
            return m.find() ? m.group(1) : "DLV-UNREF";
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
