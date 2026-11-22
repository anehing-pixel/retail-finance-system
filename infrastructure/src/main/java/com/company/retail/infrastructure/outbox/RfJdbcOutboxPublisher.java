package com.company.retail.infrastructure.outbox;

import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.common.contract.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link OutboxPublisher} 的 JDBC 实现（对齐 floorplan 的 JdbcOutboxPublisher 机制）。
 *
 * <p><b>事务边界（关键，别改）</b>：本类<b>不加</b> {@code @Transactional}。
 * 「业务写 + 事件写」必须是同一个本地事务；事件写入跟随调用方事务，
 * 由 application 层的一个用例一个事务边界统一控制。</p>
 *
 * <p><b>与 floorplan 版的两处差异</b>（均为 R03 §4 切断点的落点）：</p>
 * <ul>
 *   <li>必填键校验用 {@code requireBizKeyAndTraceId(payload, bizKeyOf(eventType))}——
 *       业务主键按事件类型声明（R09 §5.1），不硬编码 vin；</li>
 *   <li>发布前先过 {@link EventPayloadGuard} 白名单（INV-4，默认拒绝）——PII 进不了 SENT 永久表。</li>
 * </ul>
 *
 * <p><b>去重</b>：同一 {@code (event_type, aggregate_key)} 已有 PENDING/SENT 记录时不再插入；
 * 已知并发窗口由消费端 {@code event_consumptions(event_id, consumer)} 幂等主键兜底（R09 §6 三层）。</p>
 */
public class RfJdbcOutboxPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(RfJdbcOutboxPublisher.class);

    /** 表在 core schema（R06 §4.8）；显式限定，不依赖连接的 search_path。 */
    private static final String INSERT_IF_ABSENT =
            "INSERT INTO core.outbox_events (event_type, aggregate_key, payload, status) "
                    + "SELECT :eventType, :aggregateKey, :payload, 'PENDING' "
                    + "WHERE NOT EXISTS (SELECT 1 FROM core.outbox_events "
                    + "                  WHERE event_type = :eventType AND aggregate_key = :aggregateKey "
                    + "                    AND status IN ('PENDING', 'SENT'))";

    private final NamedParameterJdbcTemplate jdbc;

    public RfJdbcOutboxPublisher(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public void publish(String eventType, String aggregateKey, String payload) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("outbox 事件的 eventType 不能为空");
        }
        if (aggregateKey == null || aggregateKey.isBlank()) {
            throw new IllegalArgumentException("outbox 事件的 aggregateKey 不能为空（消费端幂等锚点）");
        }
        // ① INV-4 白名单（默认拒绝）；② 业务主键 + traceId 必填（切断点 ① 参数化）。
        // 校验刻意放在 SQL 之前：非法事件绝不落库——写进 SENT 表的 PII 撤不回。
        EventPayloadGuard.validate(eventType, payload);

        Map<String, Object> params = new HashMap<>();
        params.put("eventType", eventType);
        params.put("aggregateKey", aggregateKey);
        params.put("payload", payload);

        int inserted = jdbc.update(INSERT_IF_ABSENT, params);
        if (inserted == 0) {
            log.info("outbox 去重命中，跳过重复写入 event_type={} aggregate_key={}", eventType, aggregateKey);
        } else {
            log.debug("outbox 事件已写入 event_type={} aggregate_key={}", eventType, aggregateKey);
        }
    }
}
