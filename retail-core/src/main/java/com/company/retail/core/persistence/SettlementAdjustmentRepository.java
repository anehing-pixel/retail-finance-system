package com.company.retail.core.persistence;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.core.mapper.SettlementAdjustmentMapper;
import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * {@code core.settlement_adjustments} 持久化（V13 表，W6 新增；append-only，一债权一次结清）。
 *
 * <p>INV-2 对拍的豁免侧流水：C-10 结清时未到期利息/折让本金/未核销费用的豁免与
 * 事务手续费增收都在这里留痕，余额对拍（{@code BalanceReconciliation}）按本表汇总。</p>
 */
@Repository
public class SettlementAdjustmentRepository {

    /** 豁免/增收汇总（对拍口径）。 */
    public record AdjustmentTotals(long principalWaiver, long interestWaiver, long feeWaiver,
                                   long settlementFee) {
    }

    private final SettlementAdjustmentMapper mapper;

    public SettlementAdjustmentRepository(SettlementAdjustmentMapper mapper) {
        this.mapper = mapper;
    }

    /** 落定结清调整（uk_settlement_loan 幂等锚点：一债权只允许一次结清调整）。 */
    public long insert(long loanId, String settlementType, long principalWaiver, long interestWaiver,
                       long feeWaiver, long settlementFee, String traceId) {
        try {
            mapper.insert(loanId, settlementType, principalWaiver, interestWaiver, feeWaiver,
                    settlementFee, traceId);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001,
                    "重复结清（uk_settlement_loan：一债权一次结清调整）");
        }
        return 1L;
    }

    public AdjustmentTotals sumByLoan(long loanId) {
        List<Map<String, Object>> rows = mapper.sumByLoan(loanId);
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
        return new AdjustmentTotals(RowValues.asLong(row, "pw"), RowValues.asLong(row, "iw"),
                RowValues.asLong(row, "fw"), RowValues.asLong(row, "sf"));
    }
}
