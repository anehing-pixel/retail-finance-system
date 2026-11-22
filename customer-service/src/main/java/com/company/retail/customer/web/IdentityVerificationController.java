package com.company.retail.customer.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.retail.customer.app.IdentityVerificationService;
import com.company.retail.domain.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 身份核验端点（R09 §3：POST 发起 / GET；权限 IDENTITY_VERIFY）。
 * C-07 挡板：材料提交 + 人工审核动作（approve/reject）流转状态。
 */
@RestController
@RequestMapping("/api/identity-verifications")
public class IdentityVerificationController {

    private final IdentityVerificationService service;

    public IdentityVerificationController(IdentityVerificationService service) {
        this.service = service;
    }

    /** 发起核验（C-07：method=MANUAL，eKYC 预留）。 */
    @PostMapping
    @RequirePermission(Permission.IDENTITY_VERIFY)
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        long customerId = Long.parseLong(String.valueOf(body.get("customerId")));
        long id = service.create(customerId, WebAuthSupport.traceId(request));
        return ApiResponse.ok(Map.of("verificationId", id), WebAuthSupport.traceId(request));
    }

    /** 提交核验材料（挡板：记录类型/附件引用/有效期/脱敏号）。 */
    public record DocumentRequest(String documentType, Long attachmentId,
                                  LocalDate expiryDate, String maskedNumber) {
    }

    @PostMapping("/{id}/documents")
    @RequirePermission(Permission.IDENTITY_VERIFY)
    public ApiResponse<Void> submitDocument(@PathVariable("id") long verificationId,
                                            @RequestBody DocumentRequest req,
                                            HttpServletRequest request) {
        service.submitDocument(verificationId, req.documentType(), req.attachmentId(),
                req.expiryDate(), req.maskedNumber(), WebAuthSupport.traceId(request));
        return ApiResponse.ok(null, WebAuthSupport.traceId(request));
    }

    /** 进入人工核验中（材料齐后）。 */
    @PostMapping("/{id}/start")
    @RequirePermission(Permission.IDENTITY_VERIFY)
    public ApiResponse<Void> start(@PathVariable("id") long verificationId, HttpServletRequest request) {
        service.start(verificationId);
        return ApiResponse.ok(null, WebAuthSupport.traceId(request));
    }

    /** 人工审核动作：approve=true → VERIFIED（发 IDENTITY_VERIFIED）；false → REJECTED。 */
    public record ReviewRequest(boolean approve, String reviewerId, String note) {
    }

    @PostMapping("/{id}/review")
    @RequirePermission(Permission.IDENTITY_VERIFY)
    public ApiResponse<Void> review(@PathVariable("id") long verificationId,
                                    @RequestBody ReviewRequest req,
                                    HttpServletRequest request) {
        service.review(verificationId, req.approve(), req.reviewerId(), WebAuthSupport.traceId(request));
        return ApiResponse.ok(null, WebAuthSupport.traceId(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable("id") long verificationId) {
        return ApiResponse.ok(service.get(verificationId), null);
    }
}
