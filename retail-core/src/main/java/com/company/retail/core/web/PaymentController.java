package com.company.retail.core.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.platform.security.RequireSubject;
import com.company.platform.security.SubjectType;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.core.application.PaymentApplicationService;
import com.company.retail.core.application.PaymentApplicationService.PaymentRequest;
import com.company.retail.core.application.PaymentApplicationService.PaymentView;
import com.company.retail.domain.model.Permission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收款端点（R09 §3：retail-core /api/payments 行）。
 * Idempotency-Key 必填（核销幂等第一层）；manual-match 走 UNMATCHED 人工工作台（X-7）。
 */
@RestController
@RequireSubject(SubjectType.EMPLOYEE)
public class PaymentController {

    private final PaymentApplicationService service;

    public PaymentController(PaymentApplicationService service) {
        this.service = service;
    }

    private static String traceId() {
        return RfSecurityConfig.traceId(
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes());
    }

    /** 入金录入 + 核销（loanId 缺省 → UNMATCHED 人工匹配，X-7）。 */
    @RequirePermission(Permission.PAYMENT_ALLOCATE)
    @PostMapping("/api/payments")
    public ApiResponse<PaymentView> allocate(@RequestBody PaymentRequest req,
                                             @RequestHeader(value = "Idempotency-Key", required = false)
                                             String idempotencyKey) {
        return ApiResponse.ok(service.allocate(req, idempotencyKey, traceId()), traceId());
    }

    /** 人工匹配（PAYMENT_MANUAL_MATCH）：UNMATCHED → 核销。 */
    @RequirePermission(Permission.PAYMENT_MANUAL_MATCH)
    @PostMapping("/api/payments/{id}/manual-match")
    public ApiResponse<PaymentView> manualMatch(@PathVariable long id,
                                                @RequestBody ManualMatchRequest req) {
        return ApiResponse.ok(service.manualMatch(id, req.loanId(), traceId()), traceId());
    }

    @GetMapping("/api/payments/{id}")
    public ApiResponse<PaymentView> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(id), traceId());
    }

    public record ManualMatchRequest(long loanId) {
    }
}
