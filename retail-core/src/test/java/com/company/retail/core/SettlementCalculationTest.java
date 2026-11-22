package com.company.retail.core;

import com.company.retail.core.domain.Loan;
import com.company.retail.core.domain.LoanStatus;
import com.company.retail.core.domain.SettlementCalculator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结清与提前结清（C-10 裁决：提前结清金额 = 剩余本金 − 减免额 + 手续费，R01 §6）+
 * 终态四分互斥（B-7）+ G10 守卫（余额精确为 0）。
 */
class SettlementCalculationTest {

    private static final Instant NOW = Instant.parse("2026-11-22T00:00:00Z");

    private static SettlementCalculator.SettleableItem item(long itemId, int periodNo,
                                                            long interestDue, long principalDue,
                                                            long paidInterest, long paidPrincipal) {
        return new SettlementCalculator.SettleableItem(itemId, periodNo, interestDue, principalDue,
                paidInterest, paidPrincipal);
    }

    // ---------- C-10 公式 ----------

    @Test
    void c10Formula_remainingPrincipalMinusWaiverPlusFee() {
        // 剩余本金 800000 − 减免 50000 + 手续费 20000 = 770000
        assertEquals(770000, SettlementCalculator.earlySettlementAmount(800000, 50000, 20000));
        assertEquals(800000, SettlementCalculator.earlySettlementAmount(800000, 0, 0));
        assertEquals(20000, SettlementCalculator.earlySettlementAmount(0, 0, 20000));
    }

    @Test
    void c10Guards_waiverCanNotExceedRemainingPrincipal_noNegativeInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> SettlementCalculator.earlySettlementAmount(1000, 2000, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SettlementCalculator.earlySettlementAmount(-1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SettlementCalculator.earlySettlementAmount(1000, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SettlementCalculator.earlySettlementAmount(1000, 0, -1));
    }

    // ---------- 提前结清关闭计划 ----------

    @Test
    void closurePlan_distributesPrincipalPaid_andWaivesInterestAndDiscount() {
        // 未清：p1 利息 20000 + 本金 400000；p2 利息 16000 + 本金 400000 → 剩余本金 800000
        List<SettlementCalculator.SettleableItem> items = List.of(
                item(1, 1, 20000, 400000, 0, 0),
                item(2, 2, 16000, 400000, 0, 0));
        SettlementCalculator.ClosurePlan plan = SettlementCalculator.planClosure(
                items, 50000, 20000, 30000 /* 费用未核销 */);

        // quote = 800000 − 50000 + 20000 = 770000；实收本金 = 750000
        assertEquals(750000, plan.principalPaidTotal());
        // 期次顺序分摊实收本金：p1 400000 全清，p2 350000（本金折让 50000）
        assertEquals(400000, plan.items().get(0).principalPaid());
        assertEquals(350000, plan.items().get(1).principalPaid());
        // 豁免：本金 50000 + 利息 36000 + 费用 30000
        assertEquals(50000, plan.principalWaiver());
        assertEquals(36000, plan.interestWaiver());
        assertEquals(30000, plan.feeWaiver());
        assertEquals(116000, plan.waivedTotal());
    }

    // ---------- Loan 聚合：提前结清账务闭环（余额精确归零） ----------

    @Test
    void earlySettlement_ledgerClosesToExactlyZero() {
        Loan loan = Loan.create(5001, 1200000, 30000);
        loan.startDisbursement();
        loan.completeDisbursement(NOW);              // ACTIVE，余额 1230000
        loan.accrueInterest(36000);                  // 计划生成，余额 1266000
        // 日常核销一部分：本金 400000 + 利息 20000（不含费用）
        loan.applyAllocation(420000);
        // 此时：费用 30000 + 未到期利息 16000 + 剩余本金 800000 = 846000
        assertEquals(846000, loan.balance());

        SettlementCalculator.ClosurePlan plan = SettlementCalculator.planClosure(
                List.of(item(2, 2, 16000, 800000, 0, 0)),
                50000, 20000, 30000);
        // quote = 800000 − 50000 + 20000 = 770000（C-10）
        assertEquals(770000, plan.principalPaidTotal() + 20000);

        // 结清事务三步：手续费计提 → 入金核销 → 豁免
        loan.levySettlementFee(20000);
        loan.applyAllocation(plan.principalPaidTotal() + 20000);
        loan.applyWaiver(plan.waivedTotal());
        loan.recordSettlementTerms(50000, 20000);
        loan.assertSettlementAllowed(); // G10：余额必须精确为 0
        loan.settle(true, NOW);

        assertEquals(0, loan.balance());
        assertEquals(LoanStatus.EARLY_SETTLED, loan.status());
        assertEquals("EARLY", loan.settlementType());
        assertTrue(loan.status().isTerminal());
    }

    @Test
    void normalSettlement_requiresExactZeroBalance_g10Guard() {
        Loan loan = Loan.create(5001, 1200000, 0);
        loan.startDisbursement();
        loan.completeDisbursement(NOW);
        loan.accrueInterest(0);
        loan.applyAllocation(1200000); // 全部核销
        loan.assertSettlementAllowed();
        loan.settle(false, NOW);
        assertEquals(LoanStatus.SETTLED, loan.status());
        assertEquals("NORMAL", loan.settlementType());
    }

    @Test
    void normalSettlement_blockedWhenBalanceNotZero() {
        Loan loan = Loan.create(5001, 1200000, 0);
        loan.startDisbursement();
        loan.completeDisbursement(NOW);
        loan.accrueInterest(0);
        loan.applyAllocation(1000000); // 还剩 200000
        assertThrows(RuntimeException.class, loan::assertSettlementAllowed);
        // 终态未迁移
        assertEquals(LoanStatus.ACTIVE, loan.status());
    }

    @Test
    void settledAndEarlySettled_areDistinctTerminals_b7() {
        Loan a = Loan.create(1, 100, 0);
        a.startDisbursement();
        a.completeDisbursement(NOW);
        a.settle(false, NOW);
        Loan b = Loan.create(2, 100, 0);
        b.startDisbursement();
        b.completeDisbursement(NOW);
        b.settle(true, NOW);
        // SETTLED 与 EARLY_SETTLED 是两个不同的终态（B-7：四终态互斥，不可合并）
        assertNotEquals(a.status(), b.status());
        assertTrue(a.status().isTerminal());
        assertTrue(b.status().isTerminal());
        // 终态不可再迁移（I-7）
        assertThrows(IllegalStateException.class, () -> a.settle(false, NOW));
        assertThrows(IllegalStateException.class, () -> b.settle(false, NOW));
    }

    @Test
    void overWaiver_isRejected() {
        Loan loan = Loan.create(1, 100, 0);
        loan.startDisbursement();
        loan.completeDisbursement(NOW);
        assertThrows(IllegalStateException.class, () -> loan.applyWaiver(1000));
    }
}
