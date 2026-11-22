package com.company.retail.core;

import com.company.retail.core.domain.RepaymentScheduleGenerator;
import com.company.retail.core.domain.ScheduleItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 还款计划金额拆分正确性（R04 U-6；金额全部整数日元，R00 §5）。
 */
class RepaymentScheduleGeneratorTest {

    private static final LocalDate FIRST = LocalDate.of(2026, 12, 10);
    private static final long PRINCIPAL = 2_000_000L; // 200 万日元

    private static void assertSumInvariants(List<ScheduleItem> items, long principal) {
        // 不变式 1：Σ principalDue == principal（本金精确拆完，一分不差）
        assertEquals(principal, items.stream().mapToLong(ScheduleItem::principalDue).sum());
        // 不变式 2：期次内自洽 amountDue == principalDue + interestDue
        for (ScheduleItem item : items) {
            assertEquals(item.amountDue(), item.principalDue() + item.interestDue());
            assertTrue(item.amountDue() >= 0 && item.principalDue() >= 0 && item.interestDue() >= 0);
        }
    }

    private static void assertMonthlyDueDates(List<ScheduleItem> items) {
        LocalDate expected = FIRST;
        for (int i = 0; i < items.size(); i++) {
            assertEquals(i + 1, items.get(i).periodNo());
            assertEquals(expected, items.get(i).dueDate());
            expected = expected.plusMonths(1);
        }
    }

    @Test
    void equalPayment_principalSplitsExactly_withMonthlyDates() {
        RepaymentScheduleGenerator generator = new RepaymentScheduleGenerator(200); // 2.0%/月
        List<ScheduleItem> items = generator.equalPayment(PRINCIPAL, FIRST, 12);
        assertEquals(12, items.size());
        assertSumInvariants(items, PRINCIPAL);
        assertMonthlyDueDates(items);
        // 利息随余额递减（等额本息主体呈递减趋势）
        assertTrue(items.get(0).interestDue() >= items.get(items.size() - 1).interestDue());
        // 全部利息为正（非零利率）
        assertTrue(items.stream().mapToLong(ScheduleItem::interestDue).sum() > 0);
    }

    @Test
    void equalPayment_zeroRate_zeroInterest() {
        RepaymentScheduleGenerator generator = new RepaymentScheduleGenerator(0);
        List<ScheduleItem> items = generator.equalPayment(PRINCIPAL, FIRST, 10);
        assertSumInvariants(items, PRINCIPAL);
        assertEquals(0, items.stream().mapToLong(ScheduleItem::interestDue).sum());
        // 0 利率下每期均分（尾差归末期）
        assertEquals(PRINCIPAL / 10, items.get(0).principalDue());
        assertEquals(PRINCIPAL - PRINCIPAL / 10 * 9, items.get(9).principalDue());
    }

    @Test
    void equalPayment_remainderAbsorbedOnLastPeriod_noRoundingLoss() {
        // 故意取不能整除的本金：2,000,003 → 等额本息路径必须一分不差
        long principal = 2_000_003L;
        List<ScheduleItem> items = new RepaymentScheduleGenerator(137).equalPayment(principal, FIRST, 7);
        assertSumInvariants(items, principal);
    }

    @Test
    void equalPrincipal_uniformShares_interestDeclining() {
        RepaymentScheduleGenerator generator = new RepaymentScheduleGenerator(200);
        List<ScheduleItem> items = generator.equalPrincipal(PRINCIPAL, FIRST, 8);
        assertEquals(8, items.size());
        assertSumInvariants(items, PRINCIPAL);
        // 本金均分、利息随余额递减
        for (int i = 1; i < items.size(); i++) {
            assertEquals(items.get(0).principalDue(), items.get(i).principalDue());
            assertTrue(items.get(i).interestDue() < items.get(i - 1).interestDue());
        }
    }

    @Test
    void bonusPayment_bonusMonthsPayDouble_andSumIsExact() {
        // 首期 2026-12，12 期 → bonusMonth=3（3 月奖金）命中的期次本金翻倍
        RepaymentScheduleGenerator generator = new RepaymentScheduleGenerator(200);
        List<ScheduleItem> items = generator.bonusPayment(PRINCIPAL, FIRST, 12, 3);
        assertSumInvariants(items, PRINCIPAL);
        // 命中奖金月的期次（2027-03）本金为非奖金期的 2 倍
        ScheduleItem march1 = items.stream().filter(i -> i.dueDate().getMonthValue() == 3)
                .findFirst().orElseThrow();
        ScheduleItem normal = items.stream().filter(i -> i.dueDate().getMonthValue() != 3)
                .findFirst().orElseThrow();
        assertEquals(normal.principalDue() * 2, march1.principalDue());
        // 12 期（2026-12 → 2027-11）恰好跨一个奖金月
        assertEquals(1, items.stream().filter(i -> i.dueDate().getMonthValue() == 3).count());
    }

    @Test
    void lumpSum_singlePeriodPrincipalPlusInterest() {
        List<ScheduleItem> items = new RepaymentScheduleGenerator(200).lumpSum(PRINCIPAL, FIRST);
        assertEquals(1, items.size());
        assertSumInvariants(items, PRINCIPAL);
        assertEquals(PRINCIPAL * 200 / 10000, items.get(0).interestDue());
    }

    @Test
    void invalidInputs_rejected() {
        List<ScheduleItem> ok = new RepaymentScheduleGenerator(200).lumpSum(1000, FIRST);
        assertEquals(1, ok.size());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RepaymentScheduleGenerator(-1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RepaymentScheduleGenerator(200).bonusPayment(1000, FIRST, 12, 13));
    }
}
