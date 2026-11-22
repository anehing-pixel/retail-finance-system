package com.company.retail.core.domain;

import java.time.LocalDate;
import java.util.Set;

/**
 * 期次状态机（R04 §3.6：{@code SCHEDULED → DUE → (PAID / PARTIAL / OVERDUE / WAIVED)}；
 * {@code OVERDUE → PAID} 允许补救）。
 *
 * <p>V6 落库默认 {@code OPEN}（R04 的 {@code SCHEDULED} 在 V6 字面为 OPEN，W2 冻结口径）。
 * <b>W6 是 schedule_items 状态的唯一写者</b>（R02 §6 权威写者）：状态变更只走本类判定，
 * 禁止直接 UPDATE ... SET status（R00 §5）。</p>
 *
 * <p>核销只作用于「已到期」的期次：OPEN（未到期）不可核销——提前入金走 UNMATCHED/人工匹配
 * 或等到期批量收编，不破坏状态机（R04 §3.6 无 OPEN→PAID 边）。</p>
 */
public enum ScheduleItemStatus {

    OPEN,
    DUE,
    PARTIAL,
    PAID,
    OVERDUE,
    WAIVED;

    /** 终态：不可再迁移（I-7）。 */
    private static final Set<ScheduleItemStatus> TERMINAL = Set.of(PAID, WAIVED);

    /** 可接收入金核销的状态（到期后；OPEN 未到期不可核销）。 */
    private static final Set<ScheduleItemStatus> ALLOCATABLE = Set.of(DUE, PARTIAL, OVERDUE);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isAllocatable() {
        return ALLOCATABLE.contains(this);
    }

    /**
     * 核销后的下一状态：全额清偿 → PAID；部分清偿 → PARTIAL；无变化 → 原状态。
     * OVERDUE → PAID 允许补救（R04 §3.6）。
     */
    public static ScheduleItemStatus afterPayment(ScheduleItemStatus current,
                                                  long unpaidBefore, long paidNow) {
        if (current.isTerminal()) {
            throw new IllegalStateException("期次终态 " + current + " 不允许再核销");
        }
        if (!current.isAllocatable()) {
            throw new IllegalStateException("期次状态 " + current + " 不允许入金核销（OPEN 未到期）");
        }
        if (paidNow <= 0) {
            return current;
        }
        return paidNow >= unpaidBefore ? PAID : PARTIAL;
    }

    /** 到期批量判定（job：schedule-due-rollup）：OPEN 且 due_date ≤ bizDate → DUE。 */
    public static boolean shouldBecomeDue(ScheduleItemStatus current, LocalDate dueDate, LocalDate bizDate) {
        return current == OPEN && !dueDate.isAfter(bizDate);
    }

    /** 到期批量判定：DUE 且 due_date < bizDate → OVERDUE（逾期按自然日，PARTIAL 不自动降级为逾期状态）。 */
    public static boolean shouldBecomeOverdue(ScheduleItemStatus current, LocalDate dueDate, LocalDate bizDate) {
        return current == DUE && dueDate.isBefore(bizDate);
    }
}
