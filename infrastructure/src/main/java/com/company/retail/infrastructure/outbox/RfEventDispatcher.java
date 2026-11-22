package com.company.retail.infrastructure.outbox;

import com.company.platform.event.DomainEvent;
import com.company.platform.event.EventConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
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

    private final NamedParameterJdbcTemplate jdbc;
    private final List<EventConsumer> consumers;

    /** ObjectProvider 而非 List 注入：多数服务没有消费者，缺 bean 走「可有可无」路径。 */
    public RfEventDispatcher(JdbcTemplate jdbc, ObjectProvider<EventConsumer> consumerProvider) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
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
        if (types == null || types.isEmpty()) {
            return;
        }
        String name = consumer.name();
        long offset = readOrCreateOffset(name);

        Map<String, Object> params = new HashMap<>();
        params.put("types", new ArrayList<>(types));
        params.put("offset", offset);
        params.put("limit", BATCH_LIMIT);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_id, event_type, aggregate_key, payload, created_at FROM core.outbox_events "
                        + "WHERE event_id > :offset AND event_type IN (:types) ORDER BY event_id LIMIT :limit",
                params);

        for (Map<String, Object> row : rows) {
            DomainEvent event = toEvent(row);
            boolean resolved = handle(consumer, event);
            if (resolved) {
                writeOffset(name, event.eventId());
                markSent(event.eventId());
            } else {
                return;
            }
        }
    }

    private boolean handle(EventConsumer consumer, DomainEvent event) {
        String name = consumer.name();
        Map<String, Object> key = new HashMap<>();
        key.put("id", event.eventId());
        key.put("consumer", name);

        jdbc.update("INSERT INTO core.event_consumptions (event_id, consumer, status, attempts) "
                + "VALUES (:id, :consumer, 'PENDING', 0) ON CONFLICT (event_id, consumer) DO NOTHING", key);

        List<Map<String, Object>> cur = jdbc.queryForList(
                "SELECT status, attempts FROM core.event_consumptions "
                        + "WHERE event_id = :id AND consumer = :consumer", key);
        String status = cur.isEmpty() ? "PENDING" : (String) cur.get(0).get("status");
        int attempts = cur.isEmpty() ? 0 : ((Number) cur.get(0).get("attempts")).intValue();

        if ("DONE".equals(status) || "DEAD".equals(status)) {
            return true;
        }
        if (attempts >= MAX_ATTEMPTS) {
            markDead(name, event, "重试次数已达上限，未再执行");
            return true;
        }

        try {
            consumer.consume(event);
            jdbc.update("UPDATE core.event_consumptions SET status = 'DONE', attempts = :next, "
                    + "last_error = NULL, updated_at = now() WHERE event_id = :id AND consumer = :consumer",
                    withNext(key, attempts + 1, null));
            log.info("事件消费成功 consumer={} event_id={} event_type={} aggregate_key={}",
                    name, event.eventId(), event.eventType(), event.aggregateKey());
            return true;
        } catch (Exception e) {
            int next = attempts + 1;
            boolean dead = next >= MAX_ATTEMPTS;
            Map<String, Object> p = withNext(key, next, truncate(describe(e)));
            jdbc.update("UPDATE core.event_consumptions SET status = " + (dead ? "'DEAD'" : "'FAILED'")
                    + ", attempts = :next, last_error = :err, updated_at = now() "
                    + "WHERE event_id = :id AND consumer = :consumer", p);
            if (dead) {
                log.error("事件消费失败且已达重试上限，置 DEAD 并跳过 consumer={} event_id={} err={}",
                        name, event.eventId(), describe(e), e);
            } else {
                log.warn("事件消费失败，游标不前进，下轮重试 consumer={} event_id={} attempts={}/{} err={}",
                        name, event.eventId(), next, MAX_ATTEMPTS, describe(e));
            }
            return dead;
        }
    }

    private Map<String, Object> withNext(Map<String, Object> key, int next, String err) {
        Map<String, Object> p = new HashMap<>(key);
        p.put("next", next);
        p.put("err", err);
        return p;
    }

    private void markDead(String consumer, DomainEvent event, String reason) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", event.eventId());
        p.put("consumer", consumer);
        p.put("err", reason);
        jdbc.update("UPDATE core.event_consumptions SET status = 'DEAD', last_error = :err, "
                + "updated_at = now() WHERE event_id = :id AND consumer = :consumer", p);
        log.error("事件已达重试上限，置 DEAD 并推进游标 consumer={} event_id={} event_type={} {}",
                consumer, event.eventId(), event.eventType(), reason);
    }

    private long readOrCreateOffset(String consumer) {
        Map<String, Object> p = new HashMap<>();
        p.put("consumer", consumer);
        jdbc.update("INSERT INTO core.consumer_offsets (consumer, last_event_id) VALUES (:consumer, 0) "
                + "ON CONFLICT (consumer) DO NOTHING", p);
        Long v = jdbc.queryForObject(
                "SELECT last_event_id FROM core.consumer_offsets WHERE consumer = :consumer",
                p, Long.class);
        return v == null ? 0L : v;
    }

    private void writeOffset(String consumer, long eventId) {
        Map<String, Object> p = new HashMap<>();
        p.put("consumer", consumer);
        p.put("id", eventId);
        jdbc.update("UPDATE core.consumer_offsets SET last_event_id = :id, updated_at = now() "
                + "WHERE consumer = :consumer AND last_event_id < :id", p);
    }

    private void markSent(long eventId) {
        jdbc.update("UPDATE core.outbox_events SET status = 'SENT', sent_at = now() "
                + "WHERE event_id = :id AND status = 'PENDING'", Map.of("id", eventId));
    }

    private DomainEvent toEvent(Map<String, Object> row) {
        long id = ((Number) row.get("event_id")).longValue();
        String type = (String) row.get("event_type");
        String key = (String) row.get("aggregate_key");
        String payload = (String) row.get("payload");
        Object createdAt = row.get("created_at");
        Instant occurredAt = createdAt instanceof Timestamp ts ? ts.toInstant() : Instant.now();
        return new DomainEvent(id, type, key, payload, occurredAt);
    }

    private String describe(Exception e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getName() : e.getClass().getSimpleName() + ": " + msg;
    }

    private String truncate(String s) {
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN) + "…";
    }
}
