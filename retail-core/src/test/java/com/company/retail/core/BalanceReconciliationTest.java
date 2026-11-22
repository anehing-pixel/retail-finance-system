package com.company.retail.core;

import com.company.retail.core.domain.BalanceReconciliation;
import com.company.retail.core.domain.LoanStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 余额对拍（INV-2 精确相等，R10 §INV；W6 内部断言 + 查询端点的领域口径）。
 */
class BalanceReconciliationTest {

    @Test
    void consistent_whenAllThreeViewsMatchExactly() {
        // 存量 846000 = 流水推导 846000 = 义务面（期次未清 816000 + 费用未清 30000）
        BalanceReconciliation.Reconciliation r = BalanceReconciliation.of(
                846000, 846000, 816000, 30000);
        assertTrue(r.consistent());
        assertEquals(846000, r.outstandingBalance());
    }

    @Test
    void inconsistent_ledgerDivergesFromStoredBalance() {
        // 存量被绕过状态机直改 → 账实不符（RF_SYS_001 断言拒绝）
        BalanceReconciliation.Reconciliation r = BalanceReconciliation.of(
                846000, 840000, 816000, 30000);
        assertFalse(r.consistent());
    }

    @Test
    void inconsistent_scheduleOutstandingDiverges() {
        BalanceReconciliation.Reconciliation r = BalanceReconciliation.of(
                846000, 846000, 800000, 30000);
        assertFalse(r.consistent());
    }

    @Test
    void zeroBalance_afterSettlement_allViewsZero() {
        BalanceReconciliation.Reconciliation r = BalanceReconciliation.of(0, 0, 0, 0);
        assertTrue(r.consistent());
    }

    @Test
    void noToleranceEvenOneYenIsRejected() {
        assertFalse(BalanceReconciliation.of(100, 100, 99, 0).consistent());
        assertFalse(BalanceReconciliation.of(100, 101, 100, 0).consistent());
        assertFalse(BalanceReconciliation.of(100, 100, 100, 1).consistent());
    }

    @Test
    void loanStatusDelinquentPath_followsR04Machine() {
        // ACTIVE → DELINQUENT → ACTIVE（入金补救）→ WRITTEN_OFF / SETTLED
        assertTrue(LoanStatus.ACTIVE.canTransitionTo(LoanStatus.DELINQUENT));
        assertTrue(LoanStatus.DELINQUENT.canTransitionTo(LoanStatus.ACTIVE));
        assertTrue(LoanStatus.DELINQUENT.canTransitionTo(LoanStatus.WRITTEN_OFF));
        assertTrue(LoanStatus.DELINQUENT.canTransitionTo(LoanStatus.SETTLED));
        // 终态四分互斥（B-7）
        assertTrue(LoanStatus.SETTLED.isTerminal());
        assertTrue(LoanStatus.EARLY_SETTLED.isTerminal());
        assertTrue(LoanStatus.WRITTEN_OFF.isTerminal());
        assertTrue(LoanStatus.CANCELLED.isTerminal());
        assertFalse(LoanStatus.SETTLED.canTransitionTo(LoanStatus.EARLY_SETTLED));
        assertFalse(LoanStatus.EARLY_SETTLED.canTransitionTo(LoanStatus.SETTLED));
    }
}
