package com.company.retail.bureau.infrastructure;

import com.company.retail.bureau.application.CreditInquiryRepository;
import com.company.retail.bureau.domain.InquiryStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/**
 * 征信查询留痕 JDBC 仓储（core.credit_inquiries；consent_id NOT NULL 由 DB 兜底，INV-5）。
 */
public class JdbcCreditInquiryRepository implements CreditInquiryRepository {

    private final JdbcTemplate jdbc;

    public JdbcCreditInquiryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(long caseId, long consentId, String bureau, String purpose, String traceId) {
        Long inquiryId = jdbc.queryForObject(
                "INSERT INTO core.credit_inquiries (case_id, consent_id, bureau, purpose, status, trace_id) "
                        + "VALUES (?, ?, ?, ?, 'REQUESTED', ?) RETURNING inquiry_id",
                Long.class, caseId, consentId, bureau, purpose, traceId);
        return inquiryId;
    }

    @Override
    public boolean markFinished(long inquiryId, InquiryStatus status, String resultSnapshotRef,
                                long costAmount, String traceId) {
        return jdbc.update("UPDATE core.credit_inquiries SET status = ?, responded_at = now(), "
                        + "result_snapshot_ref = ?, cost_amount = ?, trace_id = ?, "
                        + "version = version + 1, updated_at = now() WHERE inquiry_id = ?",
                status.name(), resultSnapshotRef, costAmount, traceId, inquiryId) > 0;
    }

    @Override
    public Optional<InquiryRow> findById(long inquiryId) {
        List<InquiryRow> rows = jdbc.query(
                "SELECT inquiry_id, case_id, consent_id, bureau, purpose, status "
                        + "FROM core.credit_inquiries WHERE inquiry_id = ?",
                (rs, i) -> new InquiryRow(rs.getLong("inquiry_id"), rs.getLong("case_id"),
                        rs.getLong("consent_id"), rs.getString("bureau"),
                        rs.getString("purpose"), rs.getString("status")),
                inquiryId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public int countFailed(long caseId, String bureau) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM core.credit_inquiries WHERE case_id = ? AND bureau = ? AND status = 'FAILED'",
                Integer.class, caseId, bureau);
        return count == null ? 0 : count;
    }
}
