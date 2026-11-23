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
import java.util.ArrayList;
import java.util.HashMap;
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
 * <p>多实例限制（如实记录）：游标按 consumer 一行，多进程同名 consumer 会互相覆盖；
 * 当前单实例部署成立，多实例前必须改 FOR UPDATE SKIP LOCKED。</p>
 */
public class RfEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RfEventDispatcher.class);

    private static final int BATCH_LIMIT = 100;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_ERROR_LEN = 500;

    private final OutboxMapper mapper;
    private final List<EventConsumer> consumers;

    // ObjectProvider 而非 List 注入：多数服务没有消费者，缺 bean 走「可有可无」路径。
    public RfEventDispatcher(OutboxMapper mapper, ObjectProvider<EventConsumer> consumerProvider) {
        this.mapper = mapper;
        this.consumers = consumerProvider.orderedStream().toList();
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        if (consumers.isEmpty()) {
            return;
        }
        for (EventConsumer consumer : consumers) {
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
            boolean resolved = handle(consumer, event);
            if (resolved) {
                writeOffset(name, event.eventId());
                mapper.markSent(event.eventId());
            } else {
                return;
            }
        }
    }

    private boolean handle(EventConsumer consumer, DomainEvent event) {
        String name = consumer.name();
        mapper.insertConsumptionIfAbsent(event.eventId(), name);

        List<Map<String, Object>> cur = mapper.findConsumption(event.eventId(), name);
        String status = cur.isEmpty() ? "PENDING" : RowValues.asString(cur.get(0), "status");
        int attempts = cur.isEmpty() ? 0 : RowValues.asInt(cur.get(0), "attempts");

        if ("DONE".equals(status) || "DEAD".equals(status)) {
            return true;
        }
        if (attempts >= MAX_ATTEMPTS) {
            markDead(name, event, "重试次数已达上限，未再执行");
            return true;
        }

        try {
            consumer.consume(event);
            mapper.markConsumptionDone(event.eventId(), name, attempts + 1);
            log.info("事件消费成功 consumer={} event_id={} event_type={} aggregate_key={}",
                    name, event.eventId(), event.eventType(), event.aggregateKey());
            return true;
        } catch (Exception e) {
            int next = attempts + 1;
            boolean dead = next >= MAX_ATTEMPTS;
            String err = truncate(describe(e));
            if (dead) {
                mapper.markConsumptionDead(event.eventId(), name, err);
                log.error("事件消费失败且已达重试上限，置 DEAD 并跳过 consumer={} event_id={} err={}",
                        name, event.eventId(), describe(e), e);
            } else {
                mapper.markConsumptionFailed(event.eventId(), name, next, err);
                log.warn("事件消费失败，游标不前进，下轮重试 consumer={} event_id={} attempts={}/{} err={}",
                        name, event.eventId(), next, MAX_ATTEMPTS, describe(e));
            }
            return dead;
        }
    }

    private void markDead(String consumer, DomainEvent event, String reason) {
        mapper.markConsumptionDead(event.eventId(), consumer, reason);
        log.error("事件已达重试上限，置 DEAD 并推进游标 consumer={} event_id={} event_type={} {}",
                consumer, event.eventId(), event.eventType(), reason);
    }

    private long readOrCreateOffset(String consumer) {
        mapper.insertOffsetIfAbsent(consumer);
        Long v = mapper.findOffset(consumer);
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

    private String describe(Exception e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getName() : e.getClass().getSimpleName() + ": " + msg;
    }

    private String truncate(String s) {
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN) + "…";
    }
}
