package com.company.retail.infrastructure.messaging;

import com.company.platform.event.DomainEvent;
import com.company.platform.event.EventConsumer;
import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeConsumer;
import com.company.retail.common.messaging.EnvelopeTopics;
import com.company.retail.infrastructure.outbox.EventConsumptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/**
 * 通用中继的消费端：把 MQ 中的事件信封还原成 {@link DomainEvent}，
 * 交给本服务的 {@link EventConsumer} 执行。
 *
 * <p><b>与直投路径共用同一张幂等表</b>（{@code core.event_consumptions}，主键 event_id + consumer），
 * 因此「直投」与「MQ 中继」同时开启也不会重复执行业务动作：谁先到谁执行，
 * 后到的看到 DONE 直接跳过。</p>
 *
 * <p><b>P0-4</b>：{@link EventConsumptionService#handle} 返回 false（失败或另一实例处理中）时
 * <b>抛出异常触发 MQ 重投</b>，不能静默 ack；已完成/终态的消费者仍按幂等跳过。</p>
 */
public class MqEventRelayConsumer implements EnvelopeConsumer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EventConsumptionService consumption;
    private final List<EventConsumer> consumers;

    public MqEventRelayConsumer(EventConsumptionService consumption, List<EventConsumer> consumers) {
        this.consumption = consumption;
        this.consumers = consumers == null ? List.of() : consumers;
    }

    @Override
    public String name() {
        return "mq-relay";
    }

    @Override
    public String topic() {
        return EnvelopeTopics.EVENT_RELAY;
    }

    @Override
    public void consume(CipherEnvelope envelope, String payloadJson) {
        JsonNode node;
        try {
            node = MAPPER.readTree(payloadJson == null ? "" : payloadJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("中继事件不是合法 JSON", e);
        }
        long eventId = node.path("eventId").asLong(0L);
        String eventType = node.path("eventType").asText("");
        String aggregateKey = node.path("aggregateKey").asText("");
        String payload = node.path("payload").asText("{}");
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(node.path("occurredAt").asText(""));
        } catch (Exception e) {
            occurredAt = Instant.now();
        }
        DomainEvent event = new DomainEvent(eventId, eventType, aggregateKey, payload, occurredAt);
        for (EventConsumer consumer : consumers) {
            if (consumer.eventTypes().contains(eventType)) {
                boolean resolved = consumption.handle(consumer, event);
                if (!resolved) {
                    throw new IllegalStateException("事件消费未完成，触发 MQ 重投 consumer="
                            + consumer.name() + " event_id=" + eventId);
                }
            }
        }
    }
}
