package com.company.retail.servicing.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 逾期天数（DPD）计算器（R04 §5.2 DpdCalculator；纯领域，零 Spring，可纯单测）。
 *
 * <p><b>口径（R05 §5「核销 + 余额 + DPD 同本地事务」的 DPD 侧）</b>：</p>
 * <ul>
 *   <li>自然日：{@code dpd = DAYS.between(due_date, today)}，跨周末/节假日连续计数（day_basis=NATURAL，V9）；</li>
 *   <li>逾期判定：{@code due_date < 今日 且 status 非已结（PAID/WAIVED）}——
 *       schedule_items 的 DUE/PAID 流转归 W6 并行开发，本口径容忍状态枚举 {OPEN, DUE, PARTIAL, PAID}；</li>
 *   <li>宽限期（⚙️ 默认 0，配置覆盖）：分层延迟生效 {@code effective = max(0, dpd - grace)}，
 *       快照 dpd_days 仍记自然日，stage 按宽限后口径；</li>
 *   <li>取所有未结逾期期次的最大值（最早未结 due_date 主导）；无逾期期次 → dpd=0 / CURRENT。</li>
 * </ul>
 */
public final class DpdCalculator {

    /** 已结状态集合：不计入逾期（PAID 已核销；WAIVED 减免）。 */
    private static final java.util.Set<String> SETTLED_ITEM_STATUSES = java.util.Set.of("PAID", "WAIVED");

    private final int gracePeriodDays;

    public DpdCalculator(int gracePeriodDays) {
        if (gracePeriodDays < 0) {
            throw new IllegalArgumentException("宽限期不能为负");
        }
        this.gracePeriodDays = gracePeriodDays;
    }

    /** 还款计划期次的只读视图（infrastructure RowMapper 提供，domain 不感知 DB 类型）。 */
    public record ScheduleItemView(long itemId, LocalDate dueDate, String status) {
    }

    /** 计算结果：自然日 dpd、宽限后分层、主导逾期期次（无逾期为空）。 */
    public record DpdResult(int dpdDays, DpdStage stage, Long overdueItemId) {
    }

    /**
     * 计算某债权在 {@code today} 的 DPD。
     *
     * @param items 该债权的全部还款计划期次
     * @param today 业务日期（自然日基准）
     */
    public DpdResult compute(List<ScheduleItemView> items, LocalDate today) {
        int maxDpd = 0;
        Long dominantItem = null;
        LocalDate dominantDue = null;
        if (items != null) {
            for (ScheduleItemView item : items) {
                if (isSettled(item.status()) || item.dueDate() == null
                        || !item.dueDate().isBefore(today)) {
                    continue;
                }
                int dpd = (int) ChronoUnit.DAYS.between(item.dueDate(), today);
                if (dpd > maxDpd || (dpd == maxDpd && dominantDue != null && item.dueDate().isBefore(dominantDue))) {
                    maxDpd = dpd;
                    dominantItem = item.itemId();
                    dominantDue = item.dueDate();
                }
            }
        }
        int effective = Math.max(0, maxDpd - gracePeriodDays);
        return new DpdResult(maxDpd, DpdStage.fromDays(effective), dominantItem);
    }

    /** 是否已结期次（不参与逾期计算）。 */
    public static boolean isSettled(String status) {
        return status != null && SETTLED_ITEM_STATUSES.contains(status);
    }
}
