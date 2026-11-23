package com.company.retail.titlerelease.messaging;

import com.company.retail.common.messaging.MqEnvelopeException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 附件密文信封的**载荷**结构（生产端组装 / 消费端解析，ADR-RF-014）。
 *
 * <p>载荷只存在于密文信封内部：broker 上永远只有 {@code CipherEnvelope} 的 8 个顶层键，
 * 本记录的字段（含文件字节的 base64）不会以明文出现在队列里。</p>
 *
 * <p><b>为什么 base64</b>：MQ 载荷走 JSON，字节要能安全嵌入字符串；
 * 代价是体积膨胀约 1/3，因此生产端按 {@code retail.mq.attachment.max-inline-bytes}
 * 阈值判定，超限回落同步路径（RocketMQ 单消息默认上限 4MB）。</p>
 *
 * @param objectKey     对象键（与信封 bizRef 一致）
 * @param contentType   内容类型
 * @param contentLength 字节长度
 * @param contentBase64 文件字节（base64）
 * @param uploadedBy    上传者（验签身份，B-9；消费者据此登记 uploaded_by）
 * @param traceId       链路追踪 ID
 */
public record AttachmentEnvelopePayload(String objectKey, String contentType, long contentLength,
                                        String contentBase64, String uploadedBy, String traceId) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 序列化为载荷 JSON（仅存在于密文内部）。 */
    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("objectKey", objectKey);
        m.put("contentType", contentType);
        m.put("contentLength", contentLength);
        m.put("contentBase64", contentBase64);
        m.put("uploadedBy", uploadedBy);
        m.put("traceId", traceId);
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            throw new MqEnvelopeException("附件载荷序列化失败", e);
        }
    }

    /**
     * 解析载荷 JSON。缺关键字段一律抛 {@link MqEnvelopeException}——半截载荷进消费端等于噪声。
     *
     * <p>异常消息只带字段名，不带任何载荷内容（载荷含文件字节，属个人信息）。</p>
     */
    public static AttachmentEnvelopePayload fromJson(String json) {
        JsonNode node;
        try {
            node = MAPPER.readTree(json == null ? "" : json);
        } catch (Exception e) {
            throw new MqEnvelopeException("附件载荷不是合法 JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new MqEnvelopeException("附件载荷不是 JSON 对象");
        }
        String objectKey = node.path("objectKey").asText("");
        String contentBase64 = node.path("contentBase64").asText("");
        if (objectKey.isBlank() || contentBase64.isBlank()) {
            throw new MqEnvelopeException("附件载荷缺少 objectKey 或 contentBase64");
        }
        return new AttachmentEnvelopePayload(
                objectKey,
                node.path("contentType").asText(""),
                node.path("contentLength").asLong(0L),
                contentBase64,
                node.path("uploadedBy").asText(""),
                node.path("traceId").asText(""));
    }
}
