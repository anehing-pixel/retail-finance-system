package com.company.retail.titlerelease.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.platform.security.RequireSubject;
import com.company.platform.security.SubjectType;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.domain.model.Permission;
import com.company.retail.titlerelease.application.TitleReleaseApplicationService;
import com.company.retail.titlerelease.application.TitleReleaseApplicationService.DocumentInput;
import com.company.retail.titlerelease.application.TitleReleaseApplicationService.EvidenceChainView;
import com.company.retail.titlerelease.application.TitleReleaseApplicationService.ReceiptResult;
import com.company.retail.titlerelease.application.TitleReleaseApplicationService.ReleaseOrderView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 解除订单端点（R09 §3：title-release-service /api/release-orders 行）——解除八步的人工驱动入口。
 * 事件驱动的第 1–2 步（LOAN_SETTLED 消费）不经本控制器。
 */
@RestController
@RequireSubject(SubjectType.EMPLOYEE)
public class ReleaseOrderController {

    private final TitleReleaseApplicationService service;

    public ReleaseOrderController(TitleReleaseApplicationService service) {
        this.service = service;
    }

    private static String traceId() {
        return RfSecurityConfig.traceId(
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes());
    }

    /** 人工建单（INV-3 守卫：债权终态证据为挡板输入 loanSettledEvidenceRef）。 */
    @RequirePermission(Permission.RELEASE_DOCUMENT_ISSUE)
    @PostMapping("/api/release-orders")
    public ApiResponse<ReleaseOrderView> create(@RequestBody CreateRequest req) {
        return ApiResponse.ok(
                service.createReleaseOrder(req.loanId(), req.loanSettledEvidenceRef(), traceId()),
                traceId());
    }

    /** 第 3 步：登记解除文件（四件套；齐套自动 → DOCUMENTS_PREPARED）。 */
    @RequirePermission(Permission.RELEASE_DOCUMENT_ISSUE)
    @PostMapping("/api/release-orders/{id}/documents")
    public ApiResponse<ReleaseOrderView> documents(@PathVariable long id,
                                                   @RequestBody List<DocumentInput> docs) {
        return ApiResponse.ok(service.prepareDocuments(id, docs, traceId()), traceId());
    }

    /** 第 4 步：発行文件并交付（→ DOCUMENTS_ISSUED + RELEASE_DOCUMENTS_ISSUED 事件）。 */
    @RequirePermission(Permission.RELEASE_DOCUMENT_ISSUE)
    @PostMapping("/api/release-orders/{id}/issue")
    public ApiResponse<ReleaseOrderView> issue(@PathVariable long id) {
        return ApiResponse.ok(service.issueDocuments(id, traceId()), traceId());
    }

    /** 第 5 步：外部提交登记申请（→ EXTERNAL_SUBMITTED，证据链⑥）。 */
    @RequirePermission(Permission.RELEASE_DOCUMENT_ISSUE)
    @PostMapping("/api/release-orders/{id}/external-submission")
    public ApiResponse<ReleaseOrderView> submitExternal(@PathVariable long id,
                                                        @RequestBody SubmissionRequest req) {
        return ApiResponse.ok(service.submitExternal(id, req.submissionRef(), traceId()), traceId());
    }

    /** 第 6–8 步：回执登记（C-11 权威分级；仅 EXTERNAL_REGISTRY 可闭环）。 */
    @RequirePermission(Permission.RELEASE_REGISTER_CONFIRM)
    @PostMapping("/api/release-orders/{id}/receipts")
    public ApiResponse<ReceiptResult> receipts(@PathVariable long id, @RequestBody ReceiptRequest req) {
        return ApiResponse.ok(
                service.registerReceipt(id, req.authority(), req.receiptRef(), req.verifiedBy(),
                        req.rawRef(), traceId()),
                traceId());
    }

    /** 证据链八条。 */
    @GetMapping("/api/release-orders/{id}/evidence-chain")
    public ApiResponse<EvidenceChainView> evidenceChain(@PathVariable long id) {
        return ApiResponse.ok(service.evidenceChain(id), traceId());
    }

    /** 按 loanId 查询解除订单（前端/运营台定位事件建单结果；无订单 data=null）。 */
    @GetMapping("/api/release-orders")
    public ApiResponse<ReleaseOrderView> byLoan(@RequestParam long loanId) {
        return ApiResponse.ok(service.findOrderByLoanId(loanId), traceId());
    }

    public record CreateRequest(long loanId, String loanSettledEvidenceRef) {
    }

    public record SubmissionRequest(String submissionRef) {
    }

    public record ReceiptRequest(String authority, String receiptRef, String verifiedBy, String rawRef) {
    }
}
