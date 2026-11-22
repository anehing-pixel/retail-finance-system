package com.company.retail.bureau.infrastructure;

import com.company.retail.bureau.application.BureauReportRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/**
 * 征信报送留痕 JDBC 仓储（integration.credit_bureau_reports；
 * uk_report_loan_trigger (loan_id, trigger) 幂等兜底；consent_id NOT NULL，INV-5）。
 */
public class JdbcBureauReportRepository implements BureauReportRepository {

    private final JdbcTemplate jdbc;

    public JdbcBureauReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(long loanId, long consentId, String bureau, String trigger, String traceId) {
        try {
            return jdbc.update("INSERT INTO integration.credit_bureau_reports "
                            + "(loan_id, consent_id, bureau, trigger, status, trace_id) "
                            + "VALUES (?, ?, ?, ?, 'PENDING', ?)",
                    loanId, consentId, bureau, trigger, traceId) > 0;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // uk_report_loan_trigger：同一 (loan_id, trigger) 已有报送留痕 → 幂等冲突（RF_CONFLICT_001）
            return false;
        }
    }

    @Override
    public boolean markFinished(long reportId, String status, String payloadRef, String responseRef,
                                String traceId) {
        return jdbc.update("UPDATE integration.credit_bureau_reports SET status = ?, payload_ref = ?, "
                        + "response_ref = COALESCE(?, response_ref), sent_at = CASE WHEN ? = 'SENT' "
                        + "THEN now() ELSE sent_at END, trace_id = ?, version = version + 1, updated_at = now() "
                        + "WHERE report_id = ?",
                status, payloadRef, responseRef, status, traceId, reportId) > 0;
    }

    @Override
    public Optional<ReportRow> findById(long reportId) {
        return queryOne("report_id = ?", reportId);
    }

    @Override
    public Optional<ReportRow> findByLoanAndTrigger(long loanId, String trigger) {
        return queryOne("loan_id = ? AND trigger = ?", loanId, trigger);
    }

    private Optional<ReportRow> queryOne(String where, Object... args) {
        List<ReportRow> rows = jdbc.query(
                "SELECT report_id, loan_id, consent_id, bureau, trigger, status "
                        + "FROM integration.credit_bureau_reports WHERE " + where,
                (rs, i) -> new ReportRow(rs.getLong("report_id"), rs.getLong("loan_id"),
                        rs.getLong("consent_id"), rs.getString("bureau"),
                        rs.getString("trigger"), rs.getString("status")),
                args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
