package com.company.retail.core.persistence;

import com.company.retail.core.domain.LoanStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * {@code core.disbursements} 持久化（V6 表）。
 * uk_disb_loan（INV-1 一合同只一次代付）+ uk_disb_idempotency 双唯一约束兜底。
 */
@Repository
public class JdbcDisbursementRepository {

    public record DisbursementRow(long disbursementId, long loanId, String payeeAccountRef, long amount,
                                  String status, String instructionRef, Timestamp paidAt,
                                  String idempotencyKey, int version) {
    }

    private static final RowMapper<DisbursementRow> MAPPER = (rs, i) -> new DisbursementRow(
            rs.getLong("disbursement_id"),
            rs.getLong("loan_id"),
            rs.getString("payee_account_ref"),
            rs.getLong("amount"),
            rs.getString("status"),
            rs.getString("instruction_ref"),
            rs.getTimestamp("paid_at"),
            rs.getString("idempotency_key"),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public JdbcDisbursementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long loanId, String payeeAccountRef, long amount, String status,
                       String instructionRef, Timestamp paidAt, String idempotencyKey, String traceId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO core.disbursements (loan_id, payee_account_ref, amount, status, "
                            + "instruction_ref, paid_at, idempotency_key, trace_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"disbursement_id"});
            ps.setLong(1, loanId);
            ps.setString(2, payeeAccountRef);
            ps.setLong(3, amount);
            ps.setString(4, status);
            ps.setString(5, instructionRef);
            ps.setTimestamp(6, paidAt);
            ps.setString(7, idempotencyKey);
            ps.setString(8, traceId);
            return ps;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    public Optional<DisbursementRow> findByLoan(long loanId) {
        List<DisbursementRow> rows = jdbc.query(
                "SELECT * FROM core.disbursements WHERE loan_id = ?", MAPPER, loanId);
        return rows.stream().findFirst();
    }

    public Optional<DisbursementRow> findById(long disbursementId) {
        List<DisbursementRow> rows = jdbc.query(
                "SELECT * FROM core.disbursements WHERE disbursement_id = ?", MAPPER, disbursementId);
        return rows.stream().findFirst();
    }
}
