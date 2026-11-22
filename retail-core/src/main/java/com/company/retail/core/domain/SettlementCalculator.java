package com.company.retail.core.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 结清计算策略（{@code SettlementCalculationPolicy}，R04 §5.2 / C-10 裁决）。
 *
 * <p><b>C-10 公式</b>（R01 §6，2026-11-22 AI 裁决，系统口径生效）：
 * {@code 提前结清金额 = 剩余本金 − 减免额 + 事务手续费}。减免额/手续费为挡板输入（可配置），
 * 减免上限待法务核实（L-12），系统侧只做非负与「减免 ≤ 剩余本金」守卫。</p>
 *
 * <p><b>账务口径</b>（INV-2 精确相等）：结清 = 入金（quote）+ 三类豁免（费用/利息/本金减免）
 * 在同一本地事务内把余额精确归零。全部结清（NORMAL）不走本策略——G10 要求末回核销完成、
 * 余额已精确为 0，只做状态迁移。</p>
 */
public final class SettlementCalculator {

    private SettlementCalculator() {
    }

    /**
     * C-10 结清金额：剩余本金 − 减免额 + 手续费。
     *
     * @throws IllegalArgumentException 输入为负、减免超过剩余本金（quote 将低于手续费，不允许）
     */
    public static long earlySettlementAmount(long remainingPrincipal, long waiverAmount,
                                             long settlementFee) {
        if (remainingPrincipal < 0 || waiverAmount < 0 || settlementFee < 0) {
            throw new IllegalArgumentException("剩余本金/减免额/手续费必须为非负整数日元");
        }
        if (waiverAmount > remainingPrincipal) {
            throw new IllegalArgumentException("减免额不得超过剩余本金（C-10 守卫；上限待 L-12 核实）");
        }
        return remainingPrincipal - waiverAmount + settlementFee;
    }

    /** 可结清期次快照（未 PAID/WAIVED 且有未清偿）。 */
    public record SettleableItem(long itemId, int periodNo,
                                 long interestDue, long principalDue,
                                 long paidInterest, long paidPrincipal) {

        public long unpaidInterest() {
            return interestDue - paidInterest;
        }

        public long unpaidPrincipal() {
            return principalDue - paidPrincipal;
        }

        public long unpaidTotal() {
            return unpaidInterest() + unpaidPrincipal();
        }
    }

    /** 单期关闭结果：paid_principal 为实收本金，waived 为豁免（利息全豁 + 折让的本金）。 */
    public record ItemClosure(long itemId, int periodNo, long principalPaid, long waived) {
    }

    /**
     * 提前结清关闭计划：入金只覆盖实收本金部分；其余（未到期利息、折让本金、未核销费用）同事务豁免。
     *
     * @param items 未清偿期次（按 period_no 升序）
     * @return 关闭计划；{@code principalPaidTotal = quote − settlementFee}（与入金核销记录的本金拆分一致）
     */
    public record ClosurePlan(List<ItemClosure> items,
                              long principalPaidTotal,   // 实收本金（入金核销记录 principal_amount）
                              long principalWaiver,      // 本金减免（= C-10 减免额）
                              long interestWaiver,       // 未到期利息豁免
                              long feeWaiver) {          // 未核销费用豁免

        /** 豁免总额（余额侧同事务扣减，INV-2）。 */
        public long waivedTotal() {
            return principalWaiver + interestWaiver + feeWaiver;
        }
    }

    /**
     * 计算提前结清的期次关闭与豁免拆分。
     *
     * @param feeOutstanding 债权费用未核销部分（随结清豁免；C-10 公式不含它，另行豁免保证归零）
     */
    public static ClosurePlan planClosure(List<SettleableItem> items, long waiverAmount,
                                          long settlementFee, long feeOutstanding) {
        long quote = earlySettlementAmount(unpaidPrincipalOf(items), waiverAmount,
                settlementFee);
        long principalPaidTotal = quote - settlementFee; // = 剩余本金 − 减免额

        List<ItemClosure> closures = new ArrayList<>();
        long remainingPaid = principalPaidTotal;
        long interestWaiver = 0;
        for (SettleableItem item : items) {
            long principalPaid = Math.min(item.unpaidPrincipal(), Math.max(remainingPaid, 0));
            remainingPaid -= principalPaid;
            long waived = item.unpaidTotal() - principalPaid; // 未到期利息 + 折让本金
            closures.add(new ItemClosure(item.itemId(), item.periodNo(), principalPaid, waived));
            interestWaiver += item.unpaidInterest();
        }
        return new ClosurePlan(List.copyOf(closures), principalPaidTotal, waiverAmount,
                interestWaiver, feeOutstanding);
    }

    private static long unpaidPrincipalOf(List<SettleableItem> items) {
        return items.stream().mapToLong(SettleableItem::unpaidPrincipal).sum();
    }
}
