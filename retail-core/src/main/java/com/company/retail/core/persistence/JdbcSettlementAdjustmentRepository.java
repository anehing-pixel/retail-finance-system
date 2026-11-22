package com.company.retail.core.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code core.settlement_adjustments} 持久化（V13 表，W6 新增；append-only，一债权一次结清）。
 *
 * <p>INV-2 对拍的豁免侧流水：C-10 结清时未到期利息/折让本金/未核销费用的豁免与
 * 事务手续费增收都在这里留痕，余额对拍（{@code BalanceReconciliation}）按本表汇总。</p>
 */
@Repository
public class JdbcSettlementAdjustmentRepository {

    /** 豁免/增收汇总（对拍口径）。 */
    public record AdjustmentTotals(long principalWaiver, long interestWaiver, long feeWaiver,
                                   long settlementFee) {
    }

    private final JdbcTemplate jdbc;

    public JdbcSettlementAdjustmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 落定结清调整（uk_settlement_loan 幂等锚点：一债权只允许一次结清调整）。 */
    public long insert(long loanId, String settlementType, long principalWaiver, long interestWaiver,
                       long feeWaiver, long settlementFee, String traceId) {
        try {
            return jdbc.update(
                    "INSERT INTO core.settlement_adjustments (loan_id, settlement_type, principal_waiver, "
                            + "interest_waiver, fee_waiver, settlement_fee, trace_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    loanId, settlementType, principalWaiver, interestWaiver, feeWaiver, settlementFee, traceId);
        } catch (DuplicateKeyException e) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_CONFLICT_001,
                    "重复结清（uk_settlement_loan：一债权一次结清调整）");
        }
    }

    public AdjustmentTotals sumByLoan(long loanId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(principal_waiver), 0) AS pw, COALESCE(SUM(interest_waiver), 0) AS iw, "
                        + "COALESCE(SUM(fee_waiver), 0) AS fw, COALESCE(SUM(settlement_fee), 0) AS sf "
                        + "FROM core.settlement_adjustments WHERE loan_id = ?",
                (rs, i) -> new AdjustmentTotals(rs.getLong("pw"), rs.getLong("iw"),
                        rs.getLong("fw"), rs.getLong("sf")),
                loanId);
    }
}
