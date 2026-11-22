package com.company.retail.core.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.platform.security.RequireSubject;
import com.company.platform.security.SubjectType;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.core.application.ReconciliationApplicationService;
import com.company.retail.core.application.SettlementApplicationService;
import com.company.retail.core.application.SettlementApplicationService.SettlementQuoteView;
import com.company.retail.core.application.SettlementApplicationService.SettlementRequest;
import com.company.retail.core.application.SettlementApplicationService.SettlementView;
import com.company.retail.core.domain.BalanceReconciliation;
import com.company.retail.domain.model.Permission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 债权账务端点扩展（W6）：结清（SETTLEMENT_CONFIRM）与余额对拍（R10 §INV）。
 */
@RestController
@RequireSubject(SubjectType.EMPLOYEE)
public class SettlementController {

    private final SettlementApplicationService settlementService;
    private final ReconciliationApplicationService reconciliationService;

    public SettlementController(SettlementApplicationService settlementService,
                                ReconciliationApplicationService reconciliationService) {
        this.settlementService = settlementService;
        this.reconciliationService = reconciliationService;
    }

    private static String traceId() {
        return RfSecurityConfig.traceId(
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes());
    }

    /** POST /api/loans/{id}/settle：结清 / 提前结清（C-10；挡板输入 waiverAmount/settlementFee）。 */
    @RequirePermission(Permission.SETTLEMENT_CONFIRM)
    @PostMapping("/api/loans/{id}/settle")
    public ApiResponse<SettlementView> settle(@PathVariable long id,
                                              @RequestBody SettlementRequest req,
                                              @RequestHeader(value = "Idempotency-Key", required = false)
                                              String idempotencyKey) {
        SettlementRequest withLoan = new SettlementRequest(id, req.type(),
                req.waiverAmount(), req.settlementFee());
        return ApiResponse.ok(settlementService.settle(withLoan, idempotencyKey, traceId()), traceId());
    }

    /** GET /api/loans/{id}/settlement-quote：提前结清金额试算（C-10 公式拆项）。 */
    @GetMapping("/api/loans/{id}/settlement-quote")
    public ApiResponse<SettlementQuoteView> quote(@PathVariable long id,
                                                  @RequestParam(defaultValue = "0") long waiverAmount,
                                                  @RequestParam(defaultValue = "0") long settlementFee) {
        return ApiResponse.ok(settlementService.quote(id, waiverAmount, settlementFee), traceId());
    }

    /** GET /api/loans/{id}/reconciliation：余额对拍（INV-2 三口径）。 */
    @GetMapping("/api/loans/{id}/reconciliation")
    public ApiResponse<BalanceReconciliation.Reconciliation> reconciliation(@PathVariable long id) {
        return ApiResponse.ok(reconciliationService.reconcile(id), traceId());
    }
}
