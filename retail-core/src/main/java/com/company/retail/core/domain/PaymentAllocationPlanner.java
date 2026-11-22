package com.company.retail.core.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 入金核销拆分策略（R04 §5.2 {@code PaymentAllocationPolicy}：入金按「費用 → 利息 → 本金」顺序核销；
 * 期次顺序内先息后本）。纯领域函数，零 Spring、可纯单测。
 *
 * <p>顺序口径（R05 §5 S9–S10：核销 + 余额 + DPD 同一本地事务）：</p>
 * <ol>
 *   <li>费用（债权级 fee_amount 未核销部分）最先核销；</li>
 *   <li>再按期次顺序（period_no 升序）逐期核销：期内利息先、本金后；</li>
 *   <li>全部期次清偿后仍有剩余 → 超额入金（X-8：转工单/退款，不自动分摊），由调用方拒绝。</li>
 * </ol>
 */
public final class PaymentAllocationPlanner {

    private PaymentAllocationPlanner() {
    }

    /** 可核销期次快照（infrastructure 传入，领域层不感知 DB 行）。 */
    public record AllocatableItem(long itemId, int periodNo, ScheduleItemStatus status,
                                  long interestDue, long principalDue,
                                  long paidInterest, long paidPrincipal) {

        /** 未核销利息（先息）。 */
        public long unpaidInterest() {
            return interestDue - paidInterest;
        }

        /** 未核销本金（后本）。 */
        public long unpaidPrincipal() {
            return principalDue - paidPrincipal;
        }

        /** 本期未清偿合计。 */
        public long unpaidTotal() {
            return unpaidInterest() + unpaidPrincipal();
        }
    }

    /** 单期核销结果。 */
    public record ItemAllocation(long itemId, int periodNo, long interestPart, long principalPart) {
    }

    /** 核销计划：feePart + 各期拆分；金额总和必须等于入金金额（调用方断言）。 */
    public record AllocationPlan(long feePart, List<ItemAllocation> items, long remainder,
                                 long interestTotal, long principalTotal) {
    }

    /**
     * 计算核销拆分。只产出计划，不改余额——落库由 application 层一个本地事务执行。
     *
     * @param feeRemaining 债权费用未核销部分（fee_amount − 已核销费用）
     * @param items        可核销期次（调用方过滤：状态 ∈ {DUE, PARTIAL, OVERDUE} 且有未清偿），按 period_no 升序
     * @param paymentAmount 入金金额（整数日元）
     */
    public static AllocationPlan plan(long feeRemaining, List<AllocatableItem> items, long paymentAmount) {
        if (feeRemaining < 0 || paymentAmount <= 0) {
            throw new IllegalArgumentException("费用余额必须非负、入金必须为正整数日元");
        }
        long remaining = paymentAmount;
        long feePart = Math.min(feeRemaining, remaining);
        remaining -= feePart;

        List<ItemAllocation> allocations = new ArrayList<>();
        long interestTotal = 0;
        long principalTotal = 0;
        for (AllocatableItem item : items) {
            if (remaining <= 0) {
                break;
            }
            if (!item.status().isAllocatable()) {
                throw new IllegalStateException("期次 " + item.periodNo() + " 状态 "
                        + item.status() + " 不可核销（OPEN 未到期）");
            }
            long interestPart = Math.min(item.unpaidInterest(), remaining);
            remaining -= interestPart;
            long principalPart = Math.min(item.unpaidPrincipal(), remaining);
            remaining -= principalPart;
            if (interestPart > 0 || principalPart > 0) {
                allocations.add(new ItemAllocation(item.itemId(), item.periodNo(),
                        interestPart, principalPart));
                interestTotal += interestPart;
                principalTotal += principalPart;
            }
        }
        // 剩余未消化 = 超额入金（X-8）；调用方据 remainder > 0 拒绝（RF_PAY_002），不自动分摊
        return new AllocationPlan(feePart, List.copyOf(allocations), remaining,
                interestTotal, principalTotal);
    }
}
