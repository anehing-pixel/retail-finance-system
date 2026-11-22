package com.company.retail.servicing.infrastructure;

import com.company.retail.servicing.application.CollectionActionRepository;
import com.company.retail.servicing.application.CollectionCaseRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 催收案件 / 动作留痕 JDBC 仓储（core.collection_cases + core.collection_actions）。
 *
 * <p>「一债权一在途催收案」由 uk_coll_loan_open 部分唯一索引兜底（PG 表内 UNIQUE 不支持
 * WHERE，V9 已建表外索引，floorplan V19 教训）；插入冲突按 23505 判重返回 false。</p>
 */
public class JdbcCollectionCaseRepository implements CollectionCaseRepository {

    private final JdbcTemplate jdbc;
    private final CollectionActionRepository actionRepository;

    public JdbcCollectionCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.actionRepository = new JdbcCollectionActionRepository(jdbc);
    }

    /** 动作留痕仓储（同库同事务）。 */
    public CollectionActionRepository actionRepository() {
        return actionRepository;
    }

    @Override
    public boolean insert(long loanId, String dpdStage, String traceId) {
        try {
            jdbc.update("INSERT INTO core.collection_cases (loan_id, dpd_stage, status, trace_id) "
                            + "VALUES (?, ?, 'OPEN', ?)", loanId, dpdStage, traceId);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;   // uk_coll_loan_open：该债权已有在途案件
        }
    }

    @Override
    public Optional<CaseRow> findById(long caseId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT case_id, loan_id, dpd_stage, status, assigned_to, version "
                        + "FROM core.collection_cases WHERE case_id = ?", caseId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toRow(rows.get(0)));
    }

    @Override
    public Optional<CaseRow> findOpenByLoan(long loanId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT case_id, loan_id, dpd_stage, status, assigned_to, version "
                        + "FROM core.collection_cases WHERE loan_id = ? AND status <> 'CLOSED' "
                        + "ORDER BY case_id DESC LIMIT 1", loanId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toRow(rows.get(0)));
    }

    @Override
    public boolean updateStatus(long caseId, String status, String assignedTo, boolean closed,
                                long expectedVersion, String traceId) {
        return jdbc.update("UPDATE core.collection_cases SET status = ?, assigned_to = ?, "
                        + "closed_at = CASE WHEN ? THEN now() ELSE closed_at END, "
                        + "trace_id = ?, version = version + 1, updated_at = now() "
                        + "WHERE case_id = ? AND version = ?",
                status, assignedTo, closed, traceId, caseId, expectedVersion) == 1;
    }

    @Override
    public List<CaseRow> list(String statusOrNull) {
        StringBuilder sql = new StringBuilder(
                "SELECT case_id, loan_id, dpd_stage, status, assigned_to, version "
                        + "FROM core.collection_cases");
        List<Object> args = new ArrayList<>();
        if (statusOrNull != null && !statusOrNull.isBlank()) {
            sql.append(" WHERE status = ?");
            args.add(statusOrNull);
        }
        sql.append(" ORDER BY case_id DESC LIMIT 200");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        return rows.stream().map(this::toRow).toList();
    }

    private CaseRow toRow(Map<String, Object> r) {
        return new CaseRow(
                ((Number) r.get("case_id")).longValue(),
                ((Number) r.get("loan_id")).longValue(),
                (String) r.get("dpd_stage"),
                (String) r.get("status"),
                (String) r.get("assigned_to"),
                ((Number) r.get("version")).longValue());
    }
}
