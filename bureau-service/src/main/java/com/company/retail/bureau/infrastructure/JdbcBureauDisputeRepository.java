package com.company.retail.bureau.infrastructure;

import com.company.retail.bureau.application.BureauDisputeRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/**
 * 征信异议工单 JDBC 仓储（integration.bureau_disputes；SLA 30 日 due_at，C-05）。
 */
public class JdbcBureauDisputeRepository implements BureauDisputeRepository {

    private final JdbcTemplate jdbc;

    public JdbcBureauDisputeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(String customerRef, String bureau, String type,
                       java.time.OffsetDateTime dueAt, String traceId) {
        Long disputeId = jdbc.queryForObject(
                "INSERT INTO integration.bureau_disputes "
                        + "(customer_ref, bureau, type, due_at, status, trace_id) "
                        + "VALUES (?, ?, ?, ?, 'OPEN', ?) RETURNING dispute_id",
                Long.class, customerRef, bureau, type, dueAt, traceId);
        return disputeId;
    }

    @Override
    public Optional<DisputeRow> findById(long disputeId) {
        List<DisputeRow> rows = jdbc.query(
                "SELECT dispute_id, customer_ref, bureau, type, due_at, status "
                        + "FROM integration.bureau_disputes WHERE dispute_id = ?",
                (rs, i) -> new DisputeRow(rs.getLong("dispute_id"), rs.getString("customer_ref"),
                        rs.getString("bureau"), rs.getString("type"),
                        rs.getObject("due_at", java.time.OffsetDateTime.class), rs.getString("status")),
                disputeId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public boolean markResolved(long disputeId, String resolutionRef, String traceId) {
        return jdbc.update("UPDATE integration.bureau_disputes SET status = 'RESOLVED', "
                        + "resolution_ref = ?, trace_id = ?, version = version + 1, updated_at = now() "
                        + "WHERE dispute_id = ?",
                resolutionRef, traceId, disputeId) > 0;
    }
}
