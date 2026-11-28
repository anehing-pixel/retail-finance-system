package com.company.retail.core;

import com.company.platform.exception.BusinessException;
import com.company.retail.core.application.RepaymentPlanApplicationService;
import com.company.retail.core.domain.ScheduleItem;
import com.company.retail.core.persistence.RepaymentPlanRepository;
import com.company.retail.domain.exception.RFErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 还款计划查询用例单测：按 loanId 查期次时必须先解析 planId。
 *
 * <p>回归背景：plan_id 与 loan_id 是两个独立序列，历史实现把 loan_id 直接当 plan_id 查，
 * 只在两序列恰好同步时成立；loan 20 回滚后 loan 22 的计划是 plan 21，读计划即报「尚无还款计划」。</p>
 *
 * <p>本仓库服务侧单测不引 Mockito（手写替身），故此处用 fake 仓储。</p>
 */
class RepaymentPlanQueryTest {

    private static ScheduleItem item(int periodNo, long amountDue) {
        return new ScheduleItem(periodNo, LocalDate.of(2026, 11, 26), amountDue,
                amountDue, 0L, "DUE", 0L);
    }

    private static final class FakePlanRepository extends RepaymentPlanRepository {
        private final Map<Long, Long> planIdByLoan = new HashMap<>();
        private final Map<Long, List<ScheduleItem>> itemsByPlan = new HashMap<>();

        private FakePlanRepository() {
            super(null);
        }

        @Override
        public Optional<Long> findPlanIdByLoanId(long loanId) {
            return Optional.ofNullable(planIdByLoan.get(loanId));
        }

        @Override
        public List<ScheduleItem> findItems(long planId) {
            return itemsByPlan.getOrDefault(planId, List.of());
        }
    }

    @Test
    void items_resolvesPlanIdByLoanId_notLoanIdAsPlanId() {
        FakePlanRepository repository = new FakePlanRepository();
        // loan 22 的计划是 plan 21；plan 22 属于别的债权（若按 loanId 直查会读到别人的计划）
        repository.planIdByLoan.put(22L, 21L);
        repository.itemsByPlan.put(21L, List.of(item(1, 410_000), item(2, 410_000), item(3, 410_000)));
        repository.itemsByPlan.put(22L, List.of(item(1, 999_999)));
        RepaymentPlanApplicationService service =
                new RepaymentPlanApplicationService(null, repository, null);

        var view = service.items(22L);

        assertEquals(22L, view.loanId());
        assertEquals(3, view.items().size());
        assertEquals(410_000L, view.items().get(0).amountDue());
    }

    @Test
    void items_loanWithoutPlan_throwsValidationWithLoanId() {
        FakePlanRepository repository = new FakePlanRepository();
        RepaymentPlanApplicationService service =
                new RepaymentPlanApplicationService(null, repository, null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.items(99L));

        assertEquals(RFErrorCode.RF_VALID_001, ex.getErrorCode());
        assertEquals("99", ex.getDetails().get("loanId"));
    }
}
