package com.company.retail.core.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.platform.security.RequireSubject;
import com.company.platform.security.SubjectType;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.core.application.LoanApplicationService;
import com.company.retail.core.application.LoanApplicationService.BalanceView;
import com.company.retail.core.application.LoanApplicationService.LoanView;
import com.company.retail.domain.model.Permission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 债权端点（R09 §3：retail-core /api/loans 行）。
 */
@RestController
@RequireSubject(SubjectType.EMPLOYEE)
public class LoanController {

    private final LoanApplicationService service;

    public LoanController(LoanApplicationService service) {
        this.service = service;
    }

    private static String traceId() {
        return RfSecurityConfig.traceId(
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes());
    }

    public record CreateLoanRequest(long contractId, long principal, long feeAmount) {
    }

    /** 债权建立（合同生效 → 生成债权记录；uk_loan_contract 幂等兜底）。 */
    @RequirePermission(Permission.DISBURSEMENT_EXEC)
    @PostMapping("/api/loans")
    public ApiResponse<LoanView> create(@RequestBody CreateLoanRequest req) {
        return ApiResponse.ok(service.create(req.contractId(), req.principal(), req.feeAmount(), traceId()),
                traceId());
    }

    @GetMapping("/api/loans/{id}")
    public ApiResponse<LoanView> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(id), traceId());
    }

    @GetMapping("/api/loans/{id}/balance")
    public ApiResponse<BalanceView> balance(@PathVariable long id) {
        return ApiResponse.ok(service.balance(id), traceId());
    }
}
