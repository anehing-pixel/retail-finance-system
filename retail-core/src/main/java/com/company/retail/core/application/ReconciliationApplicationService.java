package com.company.retail.core.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.core.domain.BalanceReconciliation;
import com.company.retail.core.domain.Loan;
import com.company.retail.core.persistence.JdbcLoanRepository;
import com.company.retail.core.persistence.JdbcPaymentRepository;
import com.company.retail.core.persistence.JdbcRepaymentPlanRepository;
import com.company.retail.core.persistence.JdbcSettlementAdjustmentRepository;
import com.company.retail.domain.exception.RFErrorCode;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 余额对拍（R10 §INV / INV-2 精确相等）：W6 内部断言 + 查询端点（R09 §3 /api/loans/{id}/balance 行的扩展）。
 *
 * <p>三口径见 {@link BalanceReconciliation}：存量余额、流水推导余额、义务面余额
 * （Σ期次未清偿 + 费用未清偿）。任一不等 → {@code RF_SYS_001}（账实不符，拒绝推进）。</p>
 */
@Service
public class ReconciliationApplicationService {

    private final JdbcLoanRepository loanRepository;
    private final JdbcRepaymentPlanRepository planRepository;
    private final JdbcPaymentRepository paymentRepository;
    private final JdbcSettlementAdjustmentRepository adjustmentRepository;

    public ReconciliationApplicationService(JdbcLoanRepository loanRepository,
                                            JdbcRepaymentPlanRepository planRepository,
                                            JdbcPaymentRepository paymentRepository,
                                            JdbcSettlementAdjustmentRepository adjustmentRepository) {
        this.loanRepository = loanRepository;
        this.planRepository = planRepository;
        this.paymentRepository = paymentRepository;
        this.adjustmentRepository = adjustmentRepository;
    }

    /** 对拍视图（GET /api/loans/{id}/reconciliation）。 */
    public BalanceReconciliation.Reconciliation reconcile(long loanId) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "债权不存在", Map.of("loanId", String.valueOf(loanId))));

        JdbcPaymentRepository.AllocationTotals alloc = paymentRepository.sumAllocationsByLoan(loanId);
        JdbcSettlementAdjustmentRepository.AdjustmentTotals adj = adjustmentRepository.sumByLoan(loanId);

        // 义务面：Σ期次未清偿（amount_due − paid − waived）+ 费用未清偿
        long scheduleOutstanding = planRepository.findPlanIdByLoanId(loanId)
                .map(planId -> planRepository.findItemRows(planId).stream()
                        .mapToLong(row -> row.amountDue() - row.paidAmount() - row.waivedAmount())
                        .sum())
                .orElse(0L);
        long feeOutstanding = loan.feeAmount() - alloc.feeTotal() - adj.feeWaiver() + adj.settlementFee();

        // 流水面：principal + fee + interest_accrued − Σ核销 − Σ豁免 + Σ结清手续费增收
        long ledgerBalance = loan.principal() + loan.feeAmount() + loan.interestAccrued()
                - alloc.grandTotal()
                - (adj.principalWaiver() + adj.interestWaiver() + adj.feeWaiver())
                + adj.settlementFee();

        return BalanceReconciliation.of(loan.balance(), ledgerBalance, scheduleOutstanding, feeOutstanding);
    }

    /** 内部断言（核销/结清本地事务内调用）：不一致即账实不符，抛 RF_SYS_001 让事务回滚。 */
    public void assertConsistent(long loanId) {
        BalanceReconciliation.Reconciliation r = reconcile(loanId);
        if (!r.consistent()) {
            throw new BusinessException(RFErrorCode.RF_SYS_001,
                    "余额对拍不一致（INV-2 账实不符，事务回滚）", Map.of(
                            "loanBalance", String.valueOf(r.loanBalance()),
                            "ledgerBalance", String.valueOf(r.ledgerBalance()),
                            "outstandingBalance", String.valueOf(r.outstandingBalance())));
        }
    }
}
