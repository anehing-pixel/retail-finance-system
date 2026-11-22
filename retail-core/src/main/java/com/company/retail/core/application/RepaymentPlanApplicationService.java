package com.company.retail.core.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.core.domain.RepaymentMethod;
import com.company.retail.core.domain.RepaymentScheduleGenerator;
import com.company.retail.core.domain.ScheduleItem;
import com.company.retail.core.persistence.JdbcLoanRepository;
import com.company.retail.core.persistence.JdbcRepaymentPlanRepository;
import com.company.retail.domain.exception.RFErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 还款计划生成用例（R09 §3：retail-core /api/repayment-plans POST，权限点 CONTRACT_ISSUE）。
 * uk_plan_loan_version：一个债权一个计划版本（本波次 version 1，重复生成 → 冲突）。
 */
@Service
public class RepaymentPlanApplicationService {

    private final JdbcLoanRepository loanRepository;
    private final JdbcRepaymentPlanRepository planRepository;

    public RepaymentPlanApplicationService(JdbcLoanRepository loanRepository,
                                           JdbcRepaymentPlanRepository planRepository) {
        this.loanRepository = loanRepository;
        this.planRepository = planRepository;
    }

    /**
     * POST /api/repayment-plans：生成还款计划。
     *
     * @param monthlyRateBp 月利率基点（200 = 2.0%/月）；期次利息 = floor(期初余额 × bp / 10000)
     */
    @Transactional
    public PlanView generate(long loanId, String method, int installmentCount, LocalDate firstDueDate,
                             int monthlyRateBp, Integer bonusMonth, String traceId) {
        var loan = loanRepository.findByIdForUpdate(loanId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "债权不存在", java.util.Map.of("loanId", String.valueOf(loanId))));
        RepaymentMethod repaymentMethod = parseMethod(method);
        if (installmentCount <= 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "期次必须为正整数");
        }
        if (firstDueDate == null) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "firstDueDate 必填（yyyy-MM-dd）");
        }
        if (bonusMonth != null && (bonusMonth < 1 || bonusMonth > 12)) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "奖金月必须是 1–12");
        }
        if (planRepository.planExistsForLoan(loanId)) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001,
                    "该债权已存在还款计划（uk_plan_loan_version）");
        }

        RepaymentScheduleGenerator generator = new RepaymentScheduleGenerator(monthlyRateBp);
        List<ScheduleItem> items = switch (repaymentMethod) {
            case EQUAL_PAYMENT -> generator.equalPayment(loan.principal(), firstDueDate, installmentCount);
            case EQUAL_PRINCIPAL -> generator.equalPrincipal(loan.principal(), firstDueDate, installmentCount);
            case BONUS_PAYMENT -> bonusMonth == null
                    ? generator.equalPayment(loan.principal(), firstDueDate, installmentCount)
                    : generator.bonusPayment(loan.principal(), firstDueDate, installmentCount, bonusMonth);
            case LUMP_SUM -> generator.lumpSum(loan.principal(), firstDueDate);
        };

        long planId = planRepository.insertPlan(loanId, repaymentMethod.name(), installmentCount,
                firstDueDate, bonusMonth, traceId);
        planRepository.insertItems(planId, items, traceId);

        // W6：应计利息入账（INV-2 的 interest_accrued 侧；计划与余额同一本地事务）
        long totalInterestDue = items.stream().mapToLong(ScheduleItem::interestDue).sum();
        loan.accrueInterest(totalInterestDue);
        if (loanRepository.updateAfterPlanGeneration(loan) == 0) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001, "债权并发更新冲突（乐观锁 version）");
        }

        return new PlanView(planId, loanId, repaymentMethod.name(), installmentCount,
                firstDueDate, bonusMonth, monthlyRateBp,
                items.stream().mapToLong(ScheduleItem::amountDue).sum(),
                items.stream().mapToLong(ScheduleItem::principalDue).sum(),
                totalInterestDue);
    }

    public PlanItemsView items(long loanId) {
        List<ScheduleItem> items = planRepository.findItems(loanId);
        if (items.isEmpty()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    "该债权尚无还款计划", java.util.Map.of("loanId", String.valueOf(loanId)));
        }
        return new PlanItemsView(loanId, items);
    }

    private static RepaymentMethod parseMethod(String method) {
        try {
            return RepaymentMethod.valueOf(method);
        } catch (Exception e) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    "method 必须是 EQUAL_PAYMENT / EQUAL_PRINCIPAL / BONUS_PAYMENT / LUMP_SUM");
        }
    }

    public record PlanView(long planId, long loanId, String method, int installmentCount,
                           LocalDate firstDueDate, Integer bonusMonth, int monthlyRateBp,
                           long totalAmountDue, long totalPrincipalDue, long totalInterestDue) {
    }

    public record PlanItemsView(long loanId, List<ScheduleItem> items) {
    }
}
