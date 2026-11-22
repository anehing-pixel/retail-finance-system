package com.company.retail.customer.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.retail.customer.app.ConsentService;
import com.company.retail.domain.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 同意端点（R09 §3：POST 获取 / DELETE 撤回（语义撤回）；权限 CONSENT_MAINTAIN）。
 * {@code /disbursement-gate} 是 frozen FeignContracts.ConsentGateClient 的 provider 侧实现。
 */
@RestController
public class ConsentController {

    private final ConsentService service;

    public ConsentController(ConsentService service) {
        this.service = service;
    }

    public record GrantRequest(long customerId, String purpose, String consentVersion,
                               String channel, Long evidenceAttachmentId, String expiresAt) {
    }

    /** 取得同意（发 CONSENT_GRANTED）。 */
    @PostMapping("/api/consents")
    @RequirePermission(Permission.CONSENT_MAINTAIN)
    public ApiResponse<Map<String, Object>> grant(@RequestBody GrantRequest req,
                                                  HttpServletRequest request) {
        long consentId = service.grant(req.customerId(), req.purpose(), req.consentVersion(),
                req.channel(), req.evidenceAttachmentId(),
                req.expiresAt() == null ? null : Instant.parse(req.expiresAt()),
                WebAuthSupport.traceId(request));
        return ApiResponse.ok(Map.of("consentId", consentId), WebAuthSupport.traceId(request));
    }

    /** 语义撤回（非物理删除；发 CONSENT_WITHDRAWN，INV-5）。 */
    public record WithdrawRequest(String reason) {
    }

    @DeleteMapping("/api/consents/{id}")
    @RequirePermission(Permission.CONSENT_MAINTAIN)
    public ApiResponse<Void> withdraw(@PathVariable("id") long consentId,
                                      @RequestBody(required = false) WithdrawRequest req,
                                      HttpServletRequest request) {
        service.withdraw(consentId, req == null ? null : req.reason(), WebAuthSupport.traceId(request));
        return ApiResponse.ok(null, WebAuthSupport.traceId(request));
    }

    /**
     * 代付门禁 provider 侧（frozen 契约对应端点）。<b>fail-closed</b>：
     * 查不到 / 已撤回 / 已过期 → allowed=false；禁缓存门禁结果（R09 §7.3）。
     */
    @GetMapping("/api/consents/{consentId}/disbursement-gate")
    public ApiResponse<com.company.retail.customer.domain.ConsentGatePolicy.GateDecision> disbursementGate(
            @PathVariable("consentId") long consentId) {
        return ApiResponse.ok(service.disbursementGate(consentId), null);
    }

    /** 同意有效性查询（customerId+purpose 口径，下游按需消费；查不到=无效）。 */
    @GetMapping("/api/consents/validity")
    public ApiResponse<Map<String, Object>> validity(@RequestParam("customerId") long customerId,
                                                     @RequestParam("purpose") String purpose) {
        return ApiResponse.ok(service.validity(customerId, purpose), null);
    }
}
