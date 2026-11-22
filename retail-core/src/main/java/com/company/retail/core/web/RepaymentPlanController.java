package com.company.retail.core.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.platform.security.RequireSubject;
import com.company.platform.security.SubjectType;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.core.application.RepaymentPlanApplicationService;
import com.company.retail.core.application.RepaymentPlanApplicationService.PlanItemsView;
import com.company.retail.core.application.RepaymentPlanApplicationService.PlanView;
import com.company.retail.domain.model.Permission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 还款计划端点（R09 §3：retail-core /api/repayment-plans，权限点 CONTRACT_ISSUE）。
 */
@RestController
@RequireSubject(SubjectType.EMPLOYEE)
public class RepaymentPlanController {

    private final RepaymentPlanApplicationService service;

    public RepaymentPlanController(RepaymentPlanApplicationService service) {
        this.service = service;
    }

    private static String traceId() {
        return RfSecurityConfig.traceId(
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes());
    }

    public record GeneratePlanRequest(long loanId, String method, int installmentCount,
                                      LocalDate firstDueDate, int monthlyRateBp, Integer bonusMonth) {
    }

    @RequirePermission(Permission.CONTRACT_ISSUE)
    @PostMapping("/api/repayment-plans")
    public ApiResponse<PlanView> generate(@RequestBody GeneratePlanRequest req) {
        return ApiResponse.ok(service.generate(req.loanId(), req.method(), req.installmentCount(),
                        req.firstDueDate(), req.monthlyRateBp(), req.bonusMonth(), traceId()),
                traceId());
    }

    @GetMapping("/api/repayment-plans")
    public ApiResponse<PlanItemsView> items(long loanId) {
        return ApiResponse.ok(service.items(loanId), traceId());
    }
}
