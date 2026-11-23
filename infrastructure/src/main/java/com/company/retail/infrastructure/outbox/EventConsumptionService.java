package com.company.retail.infrastructure.outbox;

import com.company.platform.event.DomainEvent;
import com.company.platform.event.EventConsumer;
import com.company.retail.common.persistence.RowValues;
import com.company.retail.infrastructure.mapper.OutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 事件消费记账与执行（直投与 MQ 中继共用，ADR-RF-014 §4）。
 *
 * <p><b>从 RfEventDispatcher 抽出</b>：直投（outbox 轮询）与中继（MQ 推送）两条路径的
 * 「幂等占位 → 状态判定 → 执行 → 记账」语义必须逐字一致，抽成一处避免两份实现漂移。
 * SQL 与判定口径不变（B-12：语义不改）。</p>
 *
 * <p><b>失败语义</b>：抛异常 → attempts+1、游标不前进、下轮重试；attempts 达 3 → 置 DEAD
 * 并推进游标（一条坏事件不得阻塞整条链路）。</p>
 */
public class EventConsumptionService {

    private static final Logger log = LoggerFactory.getLogger(EventConsumptionService.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_ERROR_LEN = 500;

    private final OutboxMapper mapper;

    public EventConsumptionService(OutboxMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 幂等执行一条事件。
     *
     * @return true 表示该事件已终态（不再需要重试）；false 表示本轮失败、下轮重来
     */
    public boolean handle(EventConsumer consumer, DomainEvent event) {
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

    private String describe(Exception e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getName() : e.getClass().getSimpleName() + ": " + msg;
    }

    private String truncate(String s) {
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN) + "…";
    }
}
