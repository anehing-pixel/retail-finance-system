package com.company.retail.servicing.infrastructure;

import com.company.retail.servicing.application.DpdSnapshotRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DPD 快照 JDBC 仓储（core.dpd_snapshots；uk_dpd_loan_date 幂等）。
 */
public class JdbcDpdSnapshotRepository implements DpdSnapshotRepository {

    private final JdbcTemplate jdbc;

    public JdbcDpdSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(long loanId, LocalDate bizDate, int dpdDays, String stage, String traceId) {
        int inserted = jdbc.update(
                "INSERT INTO core.dpd_snapshots (loan_id, biz_date, dpd_days, stage, day_basis, trace_id) "
                        + "VALUES (?, ?, ?, ?, 'NATURAL', ?) "
                        + "ON CONFLICT (loan_id, biz_date) DO NOTHING",
                loanId, bizDate, dpdDays, stage, traceId);
        return inserted == 1;
    }

    @Override
    public Optional<SnapshotRow> findLatestBefore(long loanId, LocalDate bizDate) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT snapshot_id, loan_id, biz_date, dpd_days, stage, day_basis "
                        + "FROM core.dpd_snapshots WHERE loan_id = ? AND biz_date < ? "
                        + "ORDER BY biz_date DESC LIMIT 1", loanId, bizDate);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toRow(rows.get(0)));
    }

    @Override
    public Optional<SnapshotRow> find(long loanId, LocalDate bizDate) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT snapshot_id, loan_id, biz_date, dpd_days, stage, day_basis "
                        + "FROM core.dpd_snapshots WHERE loan_id = ? AND biz_date = ?",
                loanId, bizDate);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toRow(rows.get(0)));
    }

    @Override
    public List<SnapshotRow> listByLoan(long loanId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT snapshot_id, loan_id, biz_date, dpd_days, stage, day_basis "
                        + "FROM core.dpd_snapshots WHERE loan_id = ? ORDER BY biz_date DESC LIMIT 200",
                loanId);
        return rows.stream().map(this::toRow).toList();
    }

    private SnapshotRow toRow(Map<String, Object> r) {
        return new SnapshotRow(
                ((Number) r.get("snapshot_id")).longValue(),
                ((Number) r.get("loan_id")).longValue(),
                ((java.sql.Date) r.get("biz_date")).toLocalDate(),
                ((Number) r.get("dpd_days")).intValue(),
                (String) r.get("stage"),
                (String) r.get("day_basis"));
    }
}
