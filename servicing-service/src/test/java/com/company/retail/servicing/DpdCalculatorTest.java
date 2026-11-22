package com.company.retail.servicing;

import com.company.retail.servicing.domain.DpdCalculator;
import com.company.retail.servicing.domain.DpdStage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * DPD 计算边界用例（R04 §7 U-8：自然日跨周末 / 宽限期 / 已结期次不逾期）。
 * 口径：due_date < 今日 且 status 非 PAID/WAIVED → 逾期；dpd = 自然日差。
 */
class DpdCalculatorTest {

    private final DpdCalculator calculator = new DpdCalculator(0);

    @Test
    void dueToday_notOverdue_dpdZero() {
        // 边界日：due_date = 今日 → 不逾期
        var r = calculator.compute(List.of(item(1, "2026-11-23", "OPEN")),
                LocalDate.parse("2026-11-23"));
        assertEquals(0, r.dpdDays());
        assertEquals(DpdStage.CURRENT, r.stage());
        assertNull(r.overdueItemId());
    }

    @Test
    void dueYesterday_dpdOne_stageM1() {
        // 边界日：due_date = 昨日 → dpd=1，M1
        var r = calculator.compute(List.of(item(1, "2026-11-22", "OPEN")),
                LocalDate.parse("2026-11-23"));
        assertEquals(1, r.dpdDays());
        assertEquals(DpdStage.M1, r.stage());
        assertEquals(1L, r.overdueItemId());
    }

    @Test
    void dueTomorrow_notOverdue() {
        var r = calculator.compute(List.of(item(1, "2026-11-24", "DUE")),
                LocalDate.parse("2026-11-23"));
        assertEquals(0, r.dpdDays());
        assertEquals(DpdStage.CURRENT, r.stage());
    }

    @Test
    void naturalDays_spanWeekend_dueFridayTodayMonday_dpdThree() {
        // 自然日口径：周五到期，周一快照 → 3 天（周末连续计数，不扣非营业日）
        var r = calculator.compute(List.of(item(1, "2026-11-20", "PARTIAL")), // Friday
                LocalDate.parse("2026-11-23"));                                // Monday
        assertEquals(3, r.dpdDays());
        assertEquals(DpdStage.M1, r.stage());
    }

    @Test
    void paidAndWaivedItems_neverOverdue() {
        var r = calculator.compute(List.of(
                        item(1, "2026-10-01", "PAID"),
                        item(2, "2026-10-02", "WAIVED")),
                LocalDate.parse("2026-11-23"));
        assertEquals(0, r.dpdDays());
        assertEquals(DpdStage.CURRENT, r.stage());
    }

    @Test
    void toleratedStatuses_openDuePartial_counted() {
        // W6 未合入时状态枚举 {OPEN, DUE, PARTIAL, PAID} 均容忍
        var r = calculator.compute(List.of(
                        item(1, "2026-11-10", "OPEN"),
                        item(2, "2026-11-05", "DUE"),
                        item(3, "2026-11-01", "PARTIAL")),
                LocalDate.parse("2026-11-23"));
        // 最早未结期次主导：11-01 → 22 天
        assertEquals(22, r.dpdDays());
        assertEquals(3L, r.overdueItemId());
        assertEquals(DpdStage.M1, r.stage());
    }

    @Test
    void stageBoundaries_m1_m2_m3_m4() {
        LocalDate today = LocalDate.parse("2026-11-23");
        assertEquals(DpdStage.M1, calculator.compute(
                List.of(item(1, today.minusDays(30).toString(), "OPEN")), today).stage());
        assertEquals(DpdStage.M2, calculator.compute(
                List.of(item(1, today.minusDays(31).toString(), "OPEN")), today).stage());
        assertEquals(DpdStage.M2, calculator.compute(
                List.of(item(1, today.minusDays(60).toString(), "OPEN")), today).stage());
        assertEquals(DpdStage.M3, calculator.compute(
                List.of(item(1, today.minusDays(61).toString(), "OPEN")), today).stage());
        assertEquals(DpdStage.M3, calculator.compute(
                List.of(item(1, today.minusDays(90).toString(), "OPEN")), today).stage());
        assertEquals(DpdStage.M4, calculator.compute(
                List.of(item(1, today.minusDays(91).toString(), "OPEN")), today).stage());
    }

    @Test
    void gracePeriod_delaysStage_notNaturalDays() {
        // 宽限期 5：自然日 10 → 分层按 5 计（M1），dpd_days 仍记自然日 10
        DpdCalculator withGrace = new DpdCalculator(5);
        var r = withGrace.compute(List.of(item(1, "2026-11-13", "OPEN")),
                LocalDate.parse("2026-11-23"));
        assertEquals(10, r.dpdDays());
        assertEquals(DpdStage.M1, r.stage());   // effective = 10 - 5 = 5 → M1
    }

    @Test
    void gracePeriod_coversShortDelay_current() {
        // 宽限期 7：逾期 5 天仍在 CURRENT（催收不触发）
        DpdCalculator withGrace = new DpdCalculator(7);
        var r = withGrace.compute(List.of(item(1, "2026-11-18", "OPEN")),
                LocalDate.parse("2026-11-23"));
        assertEquals(5, r.dpdDays());
        assertEquals(DpdStage.CURRENT, r.stage());
    }

    @Test
    void emptyPlan_current() {
        var r = calculator.compute(List.of(), LocalDate.parse("2026-11-23"));
        assertEquals(0, r.dpdDays());
        assertEquals(DpdStage.CURRENT, r.stage());
    }

    private static DpdCalculator.ScheduleItemView item(long id, String dueDate, String status) {
        return new DpdCalculator.ScheduleItemView(id, LocalDate.parse(dueDate), status);
    }
}
