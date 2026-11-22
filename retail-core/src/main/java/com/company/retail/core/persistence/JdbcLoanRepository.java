package com.company.retail.core.persistence;

import com.company.retail.core.domain.Loan;
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
 * {@code core.loans} 持久化（V6 表 + V13 结清列；权威写者 BC-6 retail-core，P-5）。
 * 账务并发口径（R06 §7）：代付/核销/结清路径一律 {@code SELECT ... FOR UPDATE} 行锁 +
 * {@code version} 乐观锁双兜底（禁止无锁读-改-写，R00 §5）。
 */
@Repository
public class JdbcLoanRepository {

    private static final RowMapper<Loan> MAPPER = (rs, i) -> Loan.restore(
            rs.getLong("loan_id"),
            rs.getLong("contract_id"),
            LoanStatus.valueOf(rs.getString("status")),
            rs.getLong("principal"),
            rs.getLong("fee_amount"),
            rs.getLong("interest_accrued"),
            rs.getLong("balance"),
            rs.getTimestamp("disbursed_at") == null ? null : rs.getTimestamp("disbursed_at").toInstant(),
            rs.getTimestamp("settled_at") == null ? null : rs.getTimestamp("settled_at").toInstant(),
            rs.getString("settlement_type"),
            rs.getLong("waiver_amount"),
            rs.getLong("settlement_fee"),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public JdbcLoanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Loan loan, String traceId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO core.loans (loan_no, contract_id, status, principal, fee_amount, balance, trace_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"loan_id"});
            ps.setString(1, loanNoOf(loan.contractId()));
            ps.setLong(2, loan.contractId());
            ps.setString(3, loan.status().name());
            ps.setLong(4, loan.principal());
            ps.setLong(5, loan.feeAmount());
            ps.setLong(6, loan.balance());
            ps.setString(7, traceId);
            return ps;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    public int updateAfterDisbursement(Loan loan) {
        return jdbc.update(
                "UPDATE core.loans SET status = ?, disbursed_at = ?, version = version + 1, updated_at = now() "
                        + "WHERE loan_id = ? AND version = ?",
                loan.status().name(),
                loan.disbursedAt() == null ? null : Timestamp.from(loan.disbursedAt()),
                loan.loanId(),
                loan.version());
    }

    /** 还款计划生成：应计利息与余额同步入账（INV-2 利息侧，W6）。 */
    public int updateAfterPlanGeneration(Loan loan) {
        return jdbc.update(
                "UPDATE core.loans SET interest_accrued = ?, balance = ?, version = version + 1, "
                        + "updated_at = now() WHERE loan_id = ? AND version = ?",
                loan.interestAccrued(), loan.balance(), loan.loanId(), loan.version());
    }

    /** 入金核销：状态（补救回 ACTIVE 口径）+ 余额乐观锁更新（W6；核销与余额同一本地事务）。 */
    public int updateAfterAllocation(Loan loan) {
        return jdbc.update(
                "UPDATE core.loans SET status = ?, balance = ?, version = version + 1, updated_at = now() "
                        + "WHERE loan_id = ? AND version = ?",
                loan.status().name(), loan.balance(), loan.loanId(), loan.version());
    }

    /** 结清：终态 + 结清三要素 + settled_at（W6；G10 后唯一入口）。 */
    public int updateAfterSettlement(Loan loan) {
        return jdbc.update(
                "UPDATE core.loans SET status = ?, balance = ?, settled_at = ?, settlement_type = ?, "
                        + "waiver_amount = ?, settlement_fee = ?, version = version + 1, updated_at = now() "
                        + "WHERE loan_id = ? AND version = ?",
                loan.status().name(), loan.balance(),
                loan.settledAt() == null ? null : Timestamp.from(loan.settledAt()),
                loan.settlementType(), loan.waiverAmount(), loan.settlementFee(),
                loan.loanId(), loan.version());
    }

    public Optional<Loan> findById(long loanId) {
        List<Loan> rows = jdbc.query("SELECT * FROM core.loans WHERE loan_id = ?", MAPPER, loanId);
        return rows.stream().findFirst();
    }

    public Optional<Loan> findByContractId(long contractId) {
        List<Loan> rows = jdbc.query("SELECT * FROM core.loans WHERE contract_id = ?", MAPPER, contractId);
        return rows.stream().findFirst();
    }

    /** 账务行锁（R06 §7：loans 更新必须 SELECT ... FOR UPDATE）。 */
    public Optional<Loan> findByIdForUpdate(long loanId) {
        List<Loan> rows = jdbc.query(
                "SELECT * FROM core.loans WHERE loan_id = ? FOR UPDATE", MAPPER, loanId);
        return rows.stream().findFirst();
    }

    /** loan_no 定源：一合同一债权（uk_loan_contract），派生键确定性唯一。 */
    public static String loanNoOf(long contractId) {
        return "LN-%d".formatted(contractId);
    }
}
