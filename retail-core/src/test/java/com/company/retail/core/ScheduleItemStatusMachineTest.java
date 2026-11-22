package com.company.retail.core;

import com.company.retail.core.domain.ScheduleItemStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 期次状态机收编（R04 §3.6：OPEN → DUE → (PAID / PARTIAL / OVERDUE / WAIVED)；OVERDUE → PAID 允许补救；
 * W6 是 schedule_items 状态唯一写者）+ 到期批量判定。
 */
class ScheduleItemStatusMachineTest {

    private static final LocalDate DUE_DATE = LocalDate.of(2026, 11, 20);

    @Test
    void dueRollup_openItemBecomesDueOnAndAfterDueDate() {
        assertTrue(ScheduleItemStatus.shouldBecomeDue(ScheduleItemStatus.OPEN, DUE_DATE,
                LocalDate.of(2026, 11, 20)));
        assertTrue(ScheduleItemStatus.shouldBecomeDue(ScheduleItemStatus.OPEN, DUE_DATE,
                LocalDate.of(2026, 11, 22)));
        // 未到期不动
        assertFalse(ScheduleItemStatus.shouldBecomeDue(ScheduleItemStatus.OPEN, DUE_DATE,
                LocalDate.of(2026, 11, 19)));
        // 非 OPEN 不收编
        assertFalse(ScheduleItemStatus.shouldBecomeDue(ScheduleItemStatus.PAID, DUE_DATE,
                LocalDate.of(2026, 11, 22)));
    }

    @Test
    void dueRollup_dueItemBecomesOverdueNextDay() {
        assertTrue(ScheduleItemStatus.shouldBecomeOverdue(ScheduleItemStatus.DUE, DUE_DATE,
                LocalDate.of(2026, 11, 21)));
        // 到期当日不算逾期；PARTIAL 不自动降级为 OVERDUE
        assertFalse(ScheduleItemStatus.shouldBecomeOverdue(ScheduleItemStatus.DUE, DUE_DATE,
                LocalDate.of(2026, 11, 20)));
        assertFalse(ScheduleItemStatus.shouldBecomeOverdue(ScheduleItemStatus.PARTIAL, DUE_DATE,
                LocalDate.of(2026, 11, 21)));
    }

    @Test
    void afterPayment_fullClearance_isPaid() {
        assertEquals(ScheduleItemStatus.PAID, ScheduleItemStatus.afterPayment(
                ScheduleItemStatus.DUE, 420000, 420000));
        // OVERDUE → PAID 允许补救
        assertEquals(ScheduleItemStatus.PAID, ScheduleItemStatus.afterPayment(
                ScheduleItemStatus.OVERDUE, 420000, 500000));
    }

    @Test
    void afterPayment_partialClearance_isPartial() {
        assertEquals(ScheduleItemStatus.PARTIAL, ScheduleItemStatus.afterPayment(
                ScheduleItemStatus.DUE, 420000, 30000));
        // PARTIAL → PARTIAL（继续部分核销）
        assertEquals(ScheduleItemStatus.PARTIAL, ScheduleItemStatus.afterPayment(
                ScheduleItemStatus.PARTIAL, 390000, 1000));
    }

    @Test
    void terminalItems_canNotBeAllocatedAgain() {
        assertThrows(IllegalStateException.class, () -> ScheduleItemStatus.afterPayment(
                ScheduleItemStatus.PAID, 0, 1000));
        assertThrows(IllegalStateException.class, () -> ScheduleItemStatus.afterPayment(
                ScheduleItemStatus.WAIVED, 0, 1000));
    }

    @Test
    void openItem_canNotBeAllocated() {
        assertThrows(IllegalStateException.class, () -> ScheduleItemStatus.afterPayment(
                ScheduleItemStatus.OPEN, 420000, 1000));
    }

    @Test
    void terminalSet_isExplicit() {
        assertTrue(ScheduleItemStatus.PAID.isTerminal());
        assertTrue(ScheduleItemStatus.WAIVED.isTerminal());
        assertFalse(ScheduleItemStatus.PARTIAL.isTerminal());
        assertFalse(ScheduleItemStatus.OVERDUE.isTerminal());
    }
}
