package com.company.retail.contract.integration;

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
 * 法定书面文件送达通道适配器（P-14，R07 §4.15；W-SIM2 六向防腐层之一）。
 *
 * <p>按提议协议 v1 信封 POST {delivery-url}/notify/v1/deliveries，受理返回回执引用。
 * 双模装配：{@code retail.contract.delivery-url} 空 = 挡板（原语义：纯 DB 交付留痕，
 * C-08），非空 = 留痕同时向送达通道推送受理。送达通知失败<b>不中断</b>交付留痕主流程
 * （通知是咨询性副本，权威留痕在 statutory_documents）。</p>
 *
 * <p>P0-7：走 ADVISORY 档——单次尝试；同一交付事件的重发共享稳定幂等键。</p>
 */
public class HttpDeliveryChannelAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHANNEL = "delivery";
    private static final String PATH = "/notify/v1/deliveries";

    private final String baseUrl;
    private final OutboundHttpClient client;

    /** 挡板/单测兼容：使用默认策略。 */
    public HttpDeliveryChannelAdapter(String baseUrl) {
        this(baseUrl, OutboundHttpClientFactory.defaults().client(CHANNEL, OutboundProfile.ADVISORY));
    }

    public HttpDeliveryChannelAdapter(String baseUrl, OutboundHttpClient client) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.client = client;
    }

    /** 送达受理推送；返回回执引用，失败返回 null（调用方只记日志，不抛）。 */
    public String notifyDelivered(long contractId, String docType, String deliveryMethod, String traceId) {
        try {
            String idempotencyKey = IdempotencyKeys.of("CTR-DELIVERY", contractId, docType,
                    deliveryMethod, traceId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contractId", contractId);
            payload.put("docType", docType);
            payload.put("deliveryMethod", deliveryMethod);
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
            Matcher matcher = Pattern.compile("\"receiptRef\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(response.getBody());
            return matcher.find() ? matcher.group(1) : "DLV-UNREF";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }
}
