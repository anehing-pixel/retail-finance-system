package com.company.retail.core.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.platform.security.RequireSubject;
import com.company.platform.security.SubjectType;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.core.application.DisbursementApplicationService;
import com.company.retail.core.application.DisbursementApplicationService.DisbursementRequest;
import com.company.retail.core.application.DisbursementApplicationService.DisbursementView;
import com.company.retail.domain.model.Permission;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 代付端点（R09 §3：retail-core /api/disbursements POST，权限点 DISBURSEMENT_EXEC）。
 * Idempotency-Key 头必填（全局唯一作用域，R09 §6）。
 */
@RestController
@RequireSubject(SubjectType.EMPLOYEE)
public class DisbursementController {

    private final DisbursementApplicationService service;

    public DisbursementController(DisbursementApplicationService service) {
        this.service = service;
    }

    private static String traceId() {
        return RfSecurityConfig.traceId(
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes());
    }

    @RequirePermission(Permission.DISBURSEMENT_EXEC)
    @PostMapping("/api/disbursements")
    public ApiResponse<DisbursementView> execute(@RequestBody DisbursementRequest req,
                                                 @RequestHeader(value = "Idempotency-Key", required = false)
                                                 String idempotencyKey) {
        return ApiResponse.ok(service.execute(req, idempotencyKey, traceId()), traceId());
    }
}
