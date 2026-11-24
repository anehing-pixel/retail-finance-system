package com.company.retail.infrastructure.outbox;

import com.company.platform.event.DomainEvent;
import com.company.platform.event.EventConsumer;
import com.company.retail.common.persistence.RowValues;
import com.company.retail.infrastructure.mapper.OutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 事件消费端框架（机制与 floorplan 的 EventDispatcher 同构，表在 core schema）。
 *
 * <p>投递语义 at-least-once：框架层 {@code core.event_consumptions(event_id, consumer)}
 * 幂等主键挡重复，业务层由消费者按业务唯一键兜底（R09 §6 幂等三层）。</p>
 *
 * <p>失败语义：消费抛异常 → attempts+1、游标不前进、下轮重试；attempts 达 3 →
 * 置 DEAD 并推进游标（一条坏事件不得阻塞整条链路）；DONE/DEAD 直接跳过。</p>
 *
 * <p><b>ADR-RF-014</b>：记账与执行逻辑已抽到 {@link EventConsumptionService}，
 * 与 MQ 中继消费端共用同一套语义与同一张幂等表——直投与中继同时开启也不会重复执行。</p>
 *
 * <p><b>多实例（P0-4）</b>：同一 {@code (event_id, consumer)} 由
 * {@link EventConsumptionService} 原子认领（PROCESSING + 5 分钟租约），只有一个实例执行；
 * 游标只前进（SQL 用 {@code GREATEST}），未抢到认领的事件本轮停止推进，由持有者完成后推进。
 * at-least-once 语义不变，业务层仍由业务唯一键兜底。</p>
 */
public class RfEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RfEventDispatcher.class);

    private static final int BATCH_LIMIT = 100;

    private final OutboxMapper mapper;
    private final List<EventConsumer> consumers;
    private final EventConsumptionService consumption;
    private final com.company.retail.infrastructure.config.MqProperties mqProperties;

    // ObjectProvider 而非 List 注入：多数服务没有消费者，缺 bean 走「可有可无」路径。
    public RfEventDispatcher(OutboxMapper mapper,
                             ObjectProvider<EventConsumer> consumerProvider,
                             EventConsumptionService consumption,
                             org.springframework.beans.factory.ObjectProvider<com.company.retail.infrastructure.config.MqProperties> mqPropertiesProvider) {
        this(mapper, consumerProvider.orderedStream().toList(), consumption,
                mqPropertiesProvider.getIfAvailable());
    }

    /** 测试/显式装配用：消费者直接传入（生产走 ObjectProvider 的精确装配）。 */
    RfEventDispatcher(OutboxMapper mapper, List<EventConsumer> consumers,
                      EventConsumptionService consumption,
                      com.company.retail.infrastructure.config.MqProperties mqProperties) {
        this.mapper = mapper;
        this.consumers = consumers == null ? List.of() : consumers;
        this.consumption = consumption;
        this.mqProperties = mqProperties;
    }

    /**
     * 直投是否让位给 MQ 中继（ADR-RF-014 §4）。
     *
     * <p>让位判定只认白名单：{@code retail.mq.direct-consumers} 里点名的消费者始终直投
     * （秒级刚性事件走 MQ 会引入秒级延迟），其余消费者在开启中继后改由 RF_EVENT_RELAY 驱动。</p>
     */
    private boolean directDeliveryOwns(EventConsumer consumer) {
        if (mqProperties == null || !mqProperties.isRelayConsumerEnabled()) {
            return true;
        }
        return mqProperties.getDirectConsumers().contains(consumer.name());
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        if (consumers.isEmpty()) {
            return;
        }
        for (EventConsumer consumer : consumers) {
            if (!directDeliveryOwns(consumer)) {
                // 已由 MQ 中继接管：直投让位，避免同事件两条路径都跑
                continue;
            }
            try {
                drain(consumer);
            } catch (Exception e) {
                // 调度线程抛异常会被 Spring 取消后续执行，等于重启前永久停摆——兜住记日志
                log.warn("事件消费轮询异常 consumer={} err={}", consumer.name(), e.toString());
            }
        }
    }

    private void drain(EventConsumer consumer) {
        Set<String> types = consumer.eventTypes();
        String name = consumer.name();
        long offset = readOrCreateOffset(name);

        List<Map<String, Object>> rows = mapper.selectEventsAfter(offset, types, BATCH_LIMIT);

        for (Map<String, Object> row : rows) {
            DomainEvent event = toEvent(row);
            boolean resolved = consumption.handle(consumer, event);
            if (resolved) {
                writeOffset(name, event.eventId());
                mapper.markSent(event.eventId());
            } else {
                return;
            }
        }
    }

    private long readOrCreateOffset(String name) {
        mapper.insertOffsetIfAbsent(name);
        Long v = mapper.findOffset(name);
        return v == null ? 0L : v;
    }

    private void writeOffset(String consumer, long eventId) {
        mapper.writeOffset(consumer, eventId);
    }

    private DomainEvent toEvent(Map<String, Object> row) {
        long id = RowValues.asLong(row, "event_id");
        String type = RowValues.asString(row, "event_type");
        String key = RowValues.asString(row, "aggregate_key");
        String payload = RowValues.asString(row, "payload");
        Instant occurredAt = RowValues.asInstant(row, "created_at");
        return new DomainEvent(id, type, key, payload, occurredAt == null ? Instant.now() : occurredAt);
    }
}
