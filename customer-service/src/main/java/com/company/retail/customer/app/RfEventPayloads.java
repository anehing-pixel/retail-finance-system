package com.company.retail.customer.app;

import com.company.retail.common.contract.OutboxPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件 payload 构造器（BC-2 生产方）：只允许白名单键（R09 §5.2），
 * 发布前仍由 {@code MyBatisOutboxPublisher → EventPayloadGuard} 兜底校验（INV-4 默认拒绝）。
 *
 * <p>口径：客户信息一律只带 {@code customerId} 等内部非个人信息引用（R09 §5.3）。</p>
 */
public final class RfEventPayloads {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RfEventPayloads() {
    }

    /**
     * 构造合规 payload：元数据（eventId/eventType/occurredAt/traceId/schemaVersion）
     * + 业务键 + 白名单内的附加字段。bizKey 字段名/值必须与 {@code OutboxPublisher.bizKeyOf} 一致。
     */
    public static String build(String eventType, String bizKeyField, String bizKeyValue,
                               String traceId, Map<String, Object> extraBizKeys) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", java.util.UUID.randomUUID().toString());
        payload.put("eventType", eventType);
        payload.put("occurredAt", java.time.Instant.now().toString());
        payload.put("traceId", traceId);
        payload.put("schemaVersion", "1.0");
        payload.put(bizKeyField, bizKeyValue);
        if (extraBizKeys != null) {
            payload.putAll(extraBizKeys);
        }
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("事件 payload 序列化失败：" + eventType, e);
        }
    }

    /** 便捷入口：发布 + 业务主键一致性双校验。 */
    public static void publish(OutboxPublisher outbox, String eventType, String bizKeyField,
                               String bizKeyValue, String traceId, Map<String, Object> extraBizKeys) {
        if (!OutboxPublisher.bizKeyOf(eventType).equals(bizKeyField)) {
            throw new IllegalArgumentException(
                    "业务主键字段与事件登记不一致（R09 §5.1）：" + eventType + " 应为 " + OutboxPublisher.bizKeyOf(eventType));
        }
        outbox.publish(eventType, bizKeyValue, build(eventType, bizKeyField, bizKeyValue, traceId, extraBizKeys));
    }
}
