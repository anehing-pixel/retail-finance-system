package com.company.retail.core.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.core.domain.PlanTerms;
import com.company.retail.core.domain.ProductProfile;
import com.company.retail.core.domain.ProductTermsResolver;
import com.company.retail.core.domain.RepaymentMethod;
import com.company.retail.core.domain.RepaymentScheduleGenerator;
import com.company.retail.core.domain.ScheduleItem;
import com.company.retail.core.persistence.LoanRepository;
import com.company.retail.core.persistence.RepaymentPlanRepository;
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

    private final LoanRepository loanRepository;
    private final RepaymentPlanRepository planRepository;
    private final ProductCatalogService productCatalog;

    public RepaymentPlanApplicationService(LoanRepository loanRepository,
                                           RepaymentPlanRepository planRepository,
                                           ProductCatalogService productCatalog) {
        this.loanRepository = loanRepository;
        this.planRepository = planRepository;
        this.productCatalog = productCatalog;
    }

    /**
     * POST /api/repayment-plans：生成还款计划。
     *
     * <p><b>参数合成口径</b>：指定 productCode 时按「产品档位 + 授信覆盖」合成生效参数
     * （优先级：授信覆盖 &gt; 档位默认，见 {@code ProductTermsResolver}）；未指定时走纯入参模式。
     * 两种模式都会把<b>实际用值快照</b>落进计划表，计划生成后即可复现当时算法。</p>
     *
     * @param installmentCount 期次；null 时取档位默认（纯入参模式下必填且为正）
     * @param monthlyRateBp    月利率基点（200 = 2.0%/月）；null 时取档位默认
     * @param productCode      产品档位码；null 或空 = 纯入参模式
     */
    @Transactional
    public PlanView generate(long loanId, String method, Integer installmentCount, LocalDate firstDueDate,
                             Integer monthlyRateBp, Integer bonusMonth, String productCode, String traceId) {
        var loan = loanRepository.findByIdForUpdate(loanId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "债权不存在", java.util.Map.of("loanId", String.valueOf(loanId))));
        RepaymentMethod repaymentMethod = parseMethod(method);
        if (firstDueDate == null) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "firstDueDate 必填（yyyy-MM-dd）");
        }
        // 参数合成：产品档位（配置） + 授信覆盖（入参）→ 生效口径（快照）
        PlanTerms terms = resolveTerms(productCode, installmentCount, monthlyRateBp, bonusMonth);
        if (planRepository.planExistsForLoan(loanId)) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001,
                    "该债权已存在还款计划（uk_plan_loan_version）");
        }

        RepaymentScheduleGenerator generator = new RepaymentScheduleGenerator(terms.rateBp());
        int termCount = terms.termCount();
        Integer effectiveBonusMonth = terms.bonusMonth();
        List<ScheduleItem> items = switch (repaymentMethod) {
            case EQUAL_PAYMENT -> generator.equalPayment(loan.principal(), firstDueDate, termCount);
            case EQUAL_PRINCIPAL -> generator.equalPrincipal(loan.principal(), firstDueDate, termCount);
            case BONUS_PAYMENT -> effectiveBonusMonth == null
                    ? generator.equalPayment(loan.principal(), firstDueDate, termCount)
                    : generator.bonusPayment(loan.principal(), firstDueDate, termCount, effectiveBonusMonth);
            case LUMP_SUM -> generator.lumpSum(loan.principal(), firstDueDate);
        };

        long planId = planRepository.insertPlan(loanId, repaymentMethod.name(), termCount,
                firstDueDate, effectiveBonusMonth, terms.productCode(), terms.productVersion(),
                terms.rateBp(), traceId);
        planRepository.insertItems(planId, items, traceId);

        // W6：应计利息入账（INV-2 的 interest_accrued 侧；计划与余额同一本地事务）
        long totalInterestDue = items.stream().mapToLong(ScheduleItem::interestDue).sum();
        loan.accrueInterest(totalInterestDue);
        if (loanRepository.updateAfterPlanGeneration(loan) == 0) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001, "债权并发更新冲突（乐观锁 version）");
        }

        return new PlanView(planId, loanId, repaymentMethod.name(), termCount,
                firstDueDate, effectiveBonusMonth, terms.rateBp(),
                terms.productCode(), terms.productVersion(),
                items.stream().mapToLong(ScheduleItem::amountDue).sum(),
                items.stream().mapToLong(ScheduleItem::principalDue).sum(),
                totalInterestDue);
    }

    /**
     * 参数合成：指定档位时「档位默认 + 授信覆盖」；未指定时纯入参。
     *
     * <p>领域策略抛 {@code IllegalArgumentException}，这里转业务异常（400 语义）。</p>
     */
    private PlanTerms resolveTerms(String productCode, Integer installmentCount,
                                   Integer monthlyRateBp, Integer bonusMonth) {
        try {
            if (productCode != null && !productCode.isBlank()) {
                ProductProfile profile = productCatalog.findEffective(productCode);
                return ProductTermsResolver.resolve(profile, installmentCount, monthlyRateBp, bonusMonth);
            }
            return ProductTermsResolver.fromDirectInput(installmentCount, monthlyRateBp, bonusMonth);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, e.getMessage());
        }
    }

    public PlanItemsView items(long loanId) {
        // plan_id 与 loan_id 是两个独立序列：必须先按 loanId 解析 planId，不能把 loanId 当 planId 查
        long planId = planRepository.findPlanIdByLoanId(loanId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "该债权尚无还款计划", java.util.Map.of("loanId", String.valueOf(loanId))));
        List<ScheduleItem> items = planRepository.findItems(planId);
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
                           String productCode, Integer productVersion,
                           long totalAmountDue, long totalPrincipalDue, long totalInterestDue) {
    }

    public record PlanItemsView(long loanId, List<ScheduleItem> items) {
    }
}
