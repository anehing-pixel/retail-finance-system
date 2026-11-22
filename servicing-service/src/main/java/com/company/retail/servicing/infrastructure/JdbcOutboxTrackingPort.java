package com.company.retail.servicing.infrastructure;

import com.company.retail.servicing.application.OutboxTrackingPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * outbox 事件状态只读跟踪 JDBC 实现（core.outbox_events，横切表只读）。
 */
public class JdbcOutboxTrackingPort implements OutboxTrackingPort {

    private final JdbcTemplate jdbc;

    public JdbcOutboxTrackingPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> latestEventStatus(String eventType, String aggregateKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status FROM core.outbox_events "
                        + "WHERE event_type = ? AND aggregate_key = ? "
                        + "ORDER BY event_id DESC LIMIT 1", eventType, aggregateKey);
        return rows.isEmpty() ? Optional.empty()
                : Optional.ofNullable((String) rows.get(0).get("status"));
    }
}
