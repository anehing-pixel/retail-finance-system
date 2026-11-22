package com.company.retail.core.persistence;

import org.springframework.dao.DuplicateKeyException;
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
 * {@code core.payments} / {@code core.payment_allocations} 持久化（V7 表；权威写者 BC-7 retail-core）。
 *
 * <p>不变式：uk_payment_account_txn（X-6 重复入金明细去重）、uk_alloc_payment（一笔入金只核销一次）。</p>
 */
@Repository
public class JdbcPaymentRepository {

    /** 入金行（GET / 核销编排用）。 */
    public record PaymentRow(long paymentId, String paymentRef, String accountRef, String transactionId,
                             long amount, Timestamp receivedAt, String channel, String status, int version) {
    }

    private static final RowMapper<PaymentRow> MAPPER = (rs, i) -> new PaymentRow(
            rs.getLong("payment_id"),
            rs.getString("payment_ref"),
            rs.getString("account_ref"),
            rs.getString("transaction_id"),
            rs.getLong("amount"),
            rs.getTimestamp("received_at"),
            rs.getString("channel"),
            rs.getString("status"),
            rs.getInt("version"));

    /** 核销汇总（对拍流水侧：Σ 费用/利息/本金 已核销）。 */
    public record AllocationTotals(long feeTotal, long interestTotal, long principalTotal) {
        public long grandTotal() {
            return feeTotal + interestTotal + principalTotal;
        }
    }

    private final JdbcTemplate jdbc;

    public JdbcPaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 入账（status=RECEIVED）；uk_payment_account_txn 兜底重复明细（X-6）。 */
    public long insert(String paymentRef, String accountRef, String transactionId, long amount,
                       Timestamp receivedAt, String channel, String status, String traceId) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO core.payments (payment_ref, account_ref, transaction_id, amount, "
                                + "received_at, channel, status, trace_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        new String[]{"payment_id"});
                ps.setString(1, paymentRef);
                ps.setString(2, accountRef);
                ps.setString(3, transactionId);
                ps.setLong(4, amount);
                ps.setTimestamp(5, receivedAt);
                ps.setString(6, channel);
                ps.setString(7, status);
                ps.setString(8, traceId);
                return ps;
            }, keys);
        } catch (DuplicateKeyException e) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_PAY_001,
                    "入金明细重复（uk_payment_account_txn，X-6 去重）");
        }
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    public Optional<PaymentRow> findById(long paymentId) {
        List<PaymentRow> rows = jdbc.query("SELECT * FROM core.payments WHERE payment_id = ?",
                MAPPER, paymentId);
        return rows.stream().findFirst();
    }

    /** 核销记录（uk_alloc_payment：一笔入金只核销一次；schedule_item_id 汇总口径置 NULL）。 */
    public long insertAllocation(long paymentId, long loanId, Long scheduleItemId,
                                 long feeAmount, long interestAmount, long principalAmount,
                                 String traceId) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO core.payment_allocations (payment_id, loan_id, schedule_item_id, "
                                + "fee_amount, interest_amount, principal_amount, allocated_at, trace_id) "
                                + "VALUES (?, ?, ?, ?, ?, ?, now(), ?)",
                        new String[]{"allocation_id"});
                ps.setLong(1, paymentId);
                ps.setLong(2, loanId);
                if (scheduleItemId == null) {
                    ps.setObject(3, null);
                } else {
                    ps.setLong(3, scheduleItemId);
                }
                ps.setLong(4, feeAmount);
                ps.setLong(5, interestAmount);
                ps.setLong(6, principalAmount);
                ps.setString(7, traceId);
                return ps;
            }, keys);
        } catch (DuplicateKeyException e) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_CONFLICT_001,
                    "重复核销（uk_alloc_payment：一笔入金只核销一次）");
        }
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    /** 流水侧核销汇总（对拍 ledgerBalance 与费用未清偿口径）。 */
    public AllocationTotals sumAllocationsByLoan(long loanId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(fee_amount), 0) AS fee_total, "
                        + "COALESCE(SUM(interest_amount), 0) AS interest_total, "
                        + "COALESCE(SUM(principal_amount), 0) AS principal_total "
                        + "FROM core.payment_allocations WHERE loan_id = ?",
                (rs, i) -> new AllocationTotals(rs.getLong("fee_total"),
                        rs.getLong("interest_total"), rs.getLong("principal_total")),
                loanId);
    }

    /** 状态迁移（RECEIVED → MATCHED/UNMATCHED → ALLOCATED；R04 §3.6 PaymentStatus）。 */
    public int updateStatus(long paymentId, String status) {
        return jdbc.update(
                "UPDATE core.payments SET status = ?, version = version + 1, updated_at = now() "
                        + "WHERE payment_id = ?",
                status, paymentId);
    }
}
