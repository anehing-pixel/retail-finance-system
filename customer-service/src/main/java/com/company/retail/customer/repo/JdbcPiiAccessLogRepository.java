package com.company.retail.customer.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * audit.pii_access_logs 追加写入（R06 §4.8：不记明文值，只记谁/何时/哪个客户/哪个字段/用途）。
 */
@Repository
public class JdbcPiiAccessLogRepository {

    private final JdbcTemplate jdbc;

    public JdbcPiiAccessLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String actorSubject, long customerId,
                       String fields, String purpose, String traceId) {
        jdbc.update("""
                INSERT INTO audit.pii_access_logs (actor, actor_subject, customer_id, fields, purpose, trace_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, actor, actorSubject, customerId, fields, purpose, traceId);
    }
}
