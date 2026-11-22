package com.company.retail.core.domain;

/**
 * 余额对拍（R10 §INV / INV-2 精确相等，W6 内部断言 + 查询端点）。
 *
 * <p><b>三口径</b>，三者必须精确相等（不容差）：</p>
 * <ol>
 *   <li>{@code loanBalance} —— core.loans.balance 存量；</li>
 *   <li>{@code ledgerBalance} —— 流水推导：principal + fee + interest_accrued − Σ核销(fee/int/prin)
 *       − Σ豁免(principal/interest/fee waiver) + Σ结清手续费增收；</li>
 *   <li>{@code outstandingBalance} —— 义务面：Σ期次未清偿(amount_due − paid − waived) + 费用未清偿
 *       （fee_amount − Σ核销费用 − Σ费用豁免 + Σ结清手续费增收）。</li>
 * </ol>
 *
 * <p>任一不等即账实不符（系统完整性事故 → {@code RF_SYS_001} 拒绝推进）。</p>
 */
public final class BalanceReconciliation {

    private BalanceReconciliation() {
    }

    public record Reconciliation(long loanBalance, long ledgerBalance, long outstandingBalance,
                                 long scheduleOutstanding, long feeOutstanding, boolean consistent) {
    }

    /** 义务面 = 期次未清偿 + 费用未清偿（均为扣减免/豁免后的净额）。 */
    public static Reconciliation of(long loanBalance, long ledgerBalance,
                                    long scheduleOutstanding, long feeOutstanding) {
        long outstanding = scheduleOutstanding + feeOutstanding;
        boolean consistent = loanBalance == ledgerBalance && loanBalance == outstanding;
        return new Reconciliation(loanBalance, ledgerBalance, outstanding,
                scheduleOutstanding, feeOutstanding, consistent);
    }
}
