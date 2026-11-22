package com.company.retail.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 还款计划生成策略（R04 §5.2 {@code RepaymentScheduleGenerator}，与法务无关可纯实现）。
 *
 * <p><b>金额口径</b>：全部整数日元（R00 §5），中间系数用 BigDecimal 计算，量化后落金额，
 * 禁止 double/float 参与金额。</p>
 *
 * <p><b>拆分不变式（本类硬保证，单测覆盖）</b>：</p>
 * <ul>
 *   <li>Σ principalDue == principal（本金精确拆完，一分不差）；</li>
 *   <li>amountDue == principalDue + interestDue（期次内自洽）；</li>
 *   <li>期次 dueDate 按月递增，首期 = firstDueDate；奖金月期次落在 bonusMonth。</li>
 * </ul>
 *
 * <p>利息按「期初余额 × 月利率基点（monthlyRateBp，如 200 = 2.0%/月）」取整去尾（floor）；
 * 尾差统一归末期吸收，保证 Σ 精确。</p>
 */
public class RepaymentScheduleGenerator {

    /** 月利率基点：200 = 2.0%（利率本身不落库，只落拆分结果）。 */
    private final int monthlyRateBp;

    public RepaymentScheduleGenerator(int monthlyRateBp) {
        if (monthlyRateBp < 0) {
            throw new IllegalArgumentException("月利率基点必须非负");
        }
        this.monthlyRateBp = monthlyRateBp;
    }

    /** 一次性还本付息：单期 principal + 全部利息。 */
    public List<ScheduleItem> lumpSum(long principal, LocalDate firstDueDate) {
        long interest = interestOf(principal);
        return List.of(new ScheduleItem(1, firstDueDate, principal + interest, principal,
                interest, ScheduleItem.STATUS_OPEN, 0));
    }

    /** 等额本息（元利均等）：年金公式定每期总额（整数日元量化），本金路径逐期模拟，尾差归末期。 */
    public List<ScheduleItem> equalPayment(long principal, LocalDate firstDueDate, int installmentCount) {
        long payment = annuityPayment(principal, installmentCount);
        long[] principalPath = new long[installmentCount];
        long balance = principal;
        for (int i = 0; i < installmentCount; i++) {
            long interest = interestOf(balance);
            long principalPart = payment - interest;
            if (principalPart > balance) {
                principalPart = balance;
            }
            if (principalPart < 0) {
                principalPart = 0; // 利息 > 期总额（超短期限/超低额度边界）：本金后置
            }
            principalPath[i] = principalPart;
            balance -= principalPart;
        }
        principalPath[installmentCount - 1] += balance; // 尾差归末期，Σ principalDue == principal
        return render(principal, firstDueDate, principalPath);
    }

    /** 等额本金（元金均等）：每期本金均分，尾差归末期；利息按递减余额计。 */
    public List<ScheduleItem> equalPrincipal(long principal, LocalDate firstDueDate, int installmentCount) {
        return render(principal, firstDueDate, splitWithRemainderOnLast(principal, installmentCount));
    }

    /**
     * 奖金还款：bonus 月（dueDate 月份 == bonusMonth）期次本金权重 ×2，其余权重 1，
     * 尾差归末期；利息仍按递减余额计（R04 U-6「三种还款方式生成；奖金月」）。
     */
    public List<ScheduleItem> bonusPayment(long principal, LocalDate firstDueDate, int installmentCount,
                                           int bonusMonth) {
        if (bonusMonth < 1 || bonusMonth > 12) {
            throw new IllegalArgumentException("奖金月必须是 1–12");
        }
        long[] weights = new long[installmentCount];
        long totalWeight = 0;
        LocalDate due = firstDueDate;
        for (int i = 0; i < installmentCount; i++) {
            weights[i] = due.getMonthValue() == bonusMonth ? 2 : 1;
            totalWeight += weights[i];
            due = due.plusMonths(1);
        }
        long[] principalPath = new long[installmentCount];
        long assigned = 0;
        for (int i = 0; i < installmentCount; i++) {
            principalPath[i] = principal * weights[i] / totalWeight;
            assigned += principalPath[i];
        }
        principalPath[installmentCount - 1] += principal - assigned; // 尾差归末期
        return render(principal, firstDueDate, principalPath);
    }

    // ------------------------------------------------------------------

    private List<ScheduleItem> render(long principal, LocalDate firstDueDate, long[] principalPath) {
        long balance = principal;
        List<ScheduleItem> items = new ArrayList<>(principalPath.length);
        LocalDate due = firstDueDate;
        for (int i = 0; i < principalPath.length; i++) {
            long interest = interestOf(balance);
            items.add(new ScheduleItem(i + 1, due, principalPath[i] + interest,
                    principalPath[i], interest, ScheduleItem.STATUS_OPEN, 0));
            balance -= principalPath[i];
            due = due.plusMonths(1);
        }
        return items;
    }

    /** 期次利息 = floor(期初余额 × 月利率基点 / 10000)。整数运算，无浮点。 */
    private long interestOf(long balance) {
        return balance * monthlyRateBp / 10000;
    }

    /** 年金公式（BigDecimal 计算，整数日元量化；利率为 0 时退化为均分）。 */
    private long annuityPayment(long principal, int count) {
        if (monthlyRateBp == 0) {
            return principal / count;
        }
        BigDecimal r = BigDecimal.valueOf(monthlyRateBp, 4); // bp/10000
        BigDecimal onePlusRn = BigDecimal.ONE.add(r).pow(count);
        BigDecimal payment = BigDecimal.valueOf(principal)
                .multiply(r)
                .multiply(onePlusRn)
                .divide(onePlusRn.subtract(BigDecimal.ONE), 0, RoundingMode.FLOOR);
        return payment.longValueExact();
    }

    /** 均分 + 尾差归末期。 */
    private long[] splitWithRemainderOnLast(long total, int count) {
        long[] path = new long[count];
        long base = total / count;
        long assigned = 0;
        for (int i = 0; i < count; i++) {
            path[i] = base;
            assigned += base;
        }
        path[count - 1] += total - assigned;
        return path;
    }
}
