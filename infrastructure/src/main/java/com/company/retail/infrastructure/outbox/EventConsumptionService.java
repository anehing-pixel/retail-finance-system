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
 * <p><b>P0-4 多实例安全</b>：执行前先用 {@code UPDATE ... WHERE status IN ('PENDING','FAILED')
 * ... RETURNING} 原子认领（{@code core.event_consumptions} 同一主键只有一行，只有一个实例能拿到）；
 * 认领后进入 {@code PROCESSING}，租约 5 分钟——进程崩溃后由其他实例过期重认领。
 * 状态写只允许认领者（{@code AND status='PROCESSING'}），避免租约被回收后旧实例覆盖新状态。</p>
 *
 * <p><b>失败语义</b>：抛异常 → attempts+1、状态 FAILED、游标不前进、下轮重试；
 * attempts 达 3 → 置 DEAD 并推进游标（一条坏事件不得阻塞整条链路）。
 * 至少一次投递语义不变，业务层仍由业务唯一键兜底（R09 §6 幂等三层）。</p>
 */
public class EventConsumptionService {

    private static final Logger log = LoggerFactory.getLogger(EventConsumptionService.class);

    private static final int MAX_ATTEMPTS = 3;
    /** PROCESSING 租约：超过该时长视为实例已崩溃，允许其他实例重认领。 */
    private static final long PROCESSING_LEASE_SECONDS = 300;
    private static final int MAX_ERROR_LEN = 500;

    private final OutboxMapper mapper;

    public EventConsumptionService(OutboxMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 幂等执行一条事件。
     *
     * @return true 表示该事件已终态（不再需要重试）；false 表示本轮失败或由其他实例处理中
     */
    public boolean handle(EventConsumer consumer, DomainEvent event) {
        String name = consumer.name();
        long eventId = event.eventId();
        mapper.insertConsumptionIfAbsent(eventId, name);

        List<Map<String, Object>> cur = mapper.findConsumption(eventId, name);
        String status = cur.isEmpty() ? "PENDING" : RowValues.asString(cur.get(0), "status");
        int attempts = cur.isEmpty() ? 0 : RowValues.asInt(cur.get(0), "attempts");

        if ("DONE".equals(status) || "DEAD".equals(status)) {
            return true;
        }
        if (attempts >= MAX_ATTEMPTS) {
            mapper.markConsumptionDeadIfExhausted(eventId, name,
                    "重试次数已达上限，未再执行", MAX_ATTEMPTS);
            log.error("事件已达重试上限，置 DEAD 并推进游标 consumer={} event_id={} event_type={}",
                    name, eventId, event.eventType());
            return true;
        }

        List<Map<String, Object>> claimed =
                mapper.claimConsumption(eventId, name, PROCESSING_LEASE_SECONDS, MAX_ATTEMPTS);
        if (claimed.isEmpty()) {
            // 其他实例持有认领（PROCESSING）或事件刚转终态：终态可推进游标，否则本轮停止
            List<Map<String, Object>> after = mapper.findConsumption(eventId, name);
            String afterStatus = after.isEmpty()
                    ? "PENDING" : RowValues.asString(after.get(0), "status");
            return "DONE".equals(afterStatus) || "DEAD".equals(afterStatus);
        }
        int claimedAttempts = RowValues.asInt(claimed.get(0), "attempts");

        try {
            consumer.consume(event);
            int updated = mapper.markConsumptionDone(eventId, name, claimedAttempts + 1);
            if (updated == 0) {
                log.warn("事件消费完成但认领已失效（租约回收）consumer={} event_id={}", name, eventId);
            }
            log.info("事件消费成功 consumer={} event_id={} event_type={} aggregate_key={}",
                    name, eventId, event.eventType(), event.aggregateKey());
            return true;
        } catch (Exception e) {
            int next = claimedAttempts + 1;
            boolean dead = next >= MAX_ATTEMPTS;
            String err = truncate(describe(e));
            if (dead) {
                mapper.markConsumptionDead(eventId, name, err);
                log.error("事件消费失败且已达重试上限，置 DEAD 并跳过 consumer={} event_id={} err={}",
                        name, eventId, describe(e), e);
            } else {
                mapper.markConsumptionFailed(eventId, name, next, err);
                log.warn("事件消费失败，游标不前进，下轮重试 consumer={} event_id={} attempts={}/{} err={}",
                        name, eventId, next, MAX_ATTEMPTS, describe(e));
            }
            return dead;
        }
    }

    private String describe(Exception e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getName() : e.getClass().getSimpleName() + ": " + msg;
    }

    private String truncate(String s) {
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN) + "…";
    }
}
