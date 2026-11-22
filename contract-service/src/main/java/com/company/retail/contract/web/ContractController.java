package com.company.retail.contract.web;

import com.company.platform.response.ApiResponse;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.contract.application.ContractApplicationService;
import com.company.retail.contract.application.ContractApplicationService.ContractView;
import com.company.retail.contract.application.ContractApplicationService.GateResult;
import com.company.retail.domain.model.Permission;
import com.company.platform.security.RequirePermission;
import com.company.platform.security.RequireSubject;
import com.company.platform.security.SubjectType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 合同域端点（R09 §3：contract-service 行；权限点 {@code CONTRACT_ISSUE}）。
 *
 * <p>门禁端点 {@code GET /api/contracts/{id}/disbursement-gate} 是 frozen
 * {@code FeignContracts.ContractGateClient} 的提供方形状（G8 fail-closed 判定源），
 * 服务间只读判定，不设写权限点。</p>
 */
@RestController
@RequireSubject(SubjectType.EMPLOYEE)
public class ContractController {

    private final ContractApplicationService service;

    public ContractController(ContractApplicationService service) {
        this.service = service;
    }

    /** traceId 贯穿（TraceIdFilter 放入请求属性）。 */
    private static String traceId() {
        return RfSecurityConfig.traceId(
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes());
    }

    public record CreateContractRequest(long applicationId, long customerId, String transactionScheme) {
    }

    @RequirePermission(Permission.CONTRACT_ISSUE)
    @PostMapping("/api/contracts")
    public ApiResponse<ContractView> create(@RequestBody CreateContractRequest req) {
        return ApiResponse.ok(
                service.create(req.applicationId(), req.customerId(), req.transactionScheme(), traceId()),
                traceId());
    }

    public record DeliverDocumentRequest(String docType, String templateVersion,
                                         String deliveryMethod, Long attachmentId) {
    }

    @RequirePermission(Permission.CONTRACT_ISSUE)
    @PostMapping("/api/contracts/{id}/documents")
    public ApiResponse<Map<String, Object>> deliverDocument(@PathVariable long id,
                                                            @RequestBody DeliverDocumentRequest req) {
        long docId = service.deliverDocument(id, req.docType(), req.templateVersion(),
                req.deliveryMethod(), req.attachmentId(), traceId());
        return ApiResponse.ok(Map.of("docId", docId, "contractId", id, "status", "DELIVERED"),
                traceId());
    }

    @RequirePermission(Permission.CONTRACT_ISSUE)
    @PostMapping("/api/contracts/{id}/documents-delivered")
    public ApiResponse<ContractView> markDocumentsDelivered(@PathVariable long id) {
        return ApiResponse.ok(service.markDocumentsDelivered(id, traceId()),
                traceId());
    }

    @RequirePermission(Permission.CONTRACT_ISSUE)
    @PostMapping("/api/contracts/{id}/sign")
    public ApiResponse<ContractView> sign(@PathVariable long id) {
        return ApiResponse.ok(service.sign(id, traceId()), traceId());
    }

    @RequirePermission(Permission.CONTRACT_ISSUE)
    @PostMapping("/api/contracts/{id}/cooling-off/confirm")
    public ApiResponse<ContractView> confirmCoolingOff(@PathVariable long id) {
        return ApiResponse.ok(service.confirmCoolingOff(id, traceId()),
                traceId());
    }

    @RequirePermission(Permission.CONTRACT_ISSUE)
    @PostMapping("/api/contracts/{id}/cancel")
    public ApiResponse<ContractView> cancel(@PathVariable long id) {
        return ApiResponse.ok(service.cancel(id, traceId()), traceId());
    }

    @GetMapping("/api/contracts/{id}")
    public ApiResponse<ContractView> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(id), traceId());
    }

    /** G8 合同门禁（frozen FeignContracts.ContractGateClient 消费此端点；fail-closed 判定源）。 */
    @GetMapping("/api/contracts/{id}/disbursement-gate")
    public ApiResponse<GateResult> disbursementGate(@PathVariable long id) {
        return ApiResponse.ok(service.disbursementGate(id), traceId());
    }
}
