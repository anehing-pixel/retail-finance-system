package com.company.retail.core;

import com.company.retail.core.domain.PaymentAllocationPlanner;
import com.company.retail.core.domain.ScheduleItemStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入金核销顺序与部分核销（R04 §5.2 PaymentAllocationPolicy：費用 → 利息 → 本金；
 * 期次顺序内先息后本；部分核销 = PARTIAL；超额入金 = X-8 拒绝）。
 */
class PaymentAllocationOrderTest {

    private static PaymentAllocationPlanner.AllocatableItem item(long itemId, int periodNo,
                                                                 ScheduleItemStatus status,
                                                                 long interestDue, long principalDue,
                                                                 long paidInterest, long paidPrincipal) {
        return new PaymentAllocationPlanner.AllocatableItem(
                itemId, periodNo, status, interestDue, principalDue, paidInterest, paidPrincipal);
    }

    @Test
    void feeIsAllocatedBeforeInterestAndPrincipal() {
        // 費用 30000 先扣，剩余 70000 进期次
        PaymentAllocationPlanner.AllocatableItem p1 = item(1, 1, ScheduleItemStatus.DUE,
                24000, 400000, 0, 0);
        PaymentAllocationPlanner.AllocationPlan plan = PaymentAllocationPlanner.plan(
                30000, List.of(p1), 100000);
        assertEquals(30000, plan.feePart());
        // 期内先息后本：利息 24000，本金 46000
        assertEquals(24000, plan.interestTotal());
        assertEquals(46000, plan.principalTotal());
        assertEquals(0, plan.remainder());
    }

    @Test
    void periodsAreAllocatedInOrder_interestFirstThenPrincipal() {
        PaymentAllocationPlanner.AllocatableItem p1 = item(1, 1, ScheduleItemStatus.DUE,
                20000, 400000, 0, 0);
        PaymentAllocationPlanner.AllocatableItem p2 = item(2, 2, ScheduleItemStatus.DUE,
                16000, 400000, 0, 0);
        PaymentAllocationPlanner.AllocationPlan plan = PaymentAllocationPlanner.plan(
                0, List.of(p1, p2), 500000);
        // 第 1 期先清：利息 20000 + 本金 400000，剩 80000 进第 2 期（利息 16000 → 本金 64000）
        assertEquals(1, plan.items().get(0).periodNo());
        assertEquals(20000, plan.items().get(0).interestPart());
        assertEquals(400000, plan.items().get(0).principalPart());
        assertEquals(16000, plan.items().get(1).interestPart());
        assertEquals(64000, plan.items().get(1).principalPart());
        assertEquals(0, plan.remainder());
    }

    @Test
    void partialPayment_leavesPrincipalUnpaid() {
        PaymentAllocationPlanner.AllocatableItem p1 = item(1, 1, ScheduleItemStatus.DUE,
                20000, 400000, 0, 0);
        PaymentAllocationPlanner.AllocationPlan plan = PaymentAllocationPlanner.plan(0, List.of(p1), 30000);
        // 利息 20000 全清，本金只核销 10000 → 部分核销（PARTIAL）
        assertEquals(20000, plan.items().get(0).interestPart());
        assertEquals(10000, plan.items().get(0).principalPart());
    }

    @Test
    void overpayment_isRejectedAsRemainder_notAutoAllocated() {
        PaymentAllocationPlanner.AllocatableItem p1 = item(1, 1, ScheduleItemStatus.DUE,
                20000, 400000, 0, 0);
        // 全部期次清偿后剩余 → remainder > 0，调用方拒绝（RF_PAY_002 / X-8）
        PaymentAllocationPlanner.AllocationPlan plan = PaymentAllocationPlanner.plan(
                0, List.of(p1), 420001);
        assertEquals(1, plan.remainder());
    }

    @Test
    void openItem_isNotAllocatable_stateMachineGuard() {
        PaymentAllocationPlanner.AllocatableItem open = item(1, 1, ScheduleItemStatus.OPEN,
                20000, 400000, 0, 0);
        // OPEN 未到期不可核销（R04 §3.6 无 OPEN→PAID 边）
        assertThrows(IllegalStateException.class,
                () -> PaymentAllocationPlanner.plan(0, List.of(open), 1000));
    }

    @Test
    void overdueItem_isAllocatable_remedyPath() {
        PaymentAllocationPlanner.AllocatableItem overdue = item(1, 1, ScheduleItemStatus.OVERDUE,
                20000, 400000, 5000, 100000);
        PaymentAllocationPlanner.AllocationPlan plan = PaymentAllocationPlanner.plan(
                0, List.of(overdue), 315000);
        // 未清：利息 15000 + 本金 300000 = 315000，全清
        assertEquals(15000, plan.items().get(0).interestPart());
        assertEquals(300000, plan.items().get(0).principalPart());
        assertEquals(0, plan.remainder());
        assertTrue(plan.interestTotal() + plan.principalTotal() == 315000);
    }
}
