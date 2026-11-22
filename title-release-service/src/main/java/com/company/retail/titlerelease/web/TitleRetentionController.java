package com.company.retail.titlerelease.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.platform.security.RequireSubject;
import com.company.platform.security.SubjectType;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.domain.model.Permission;
import com.company.retail.titlerelease.application.TitleReleaseApplicationService;
import com.company.retail.titlerelease.application.TitleReleaseApplicationService.TitleRetentionView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 保留登记端点（R09 §3：title-release-service /api/title-retentions 行）。
 */
@RestController
@RequireSubject(SubjectType.EMPLOYEE)
public class TitleRetentionController {

    private final TitleReleaseApplicationService service;

    public TitleRetentionController(TitleReleaseApplicationService service) {
        this.service = service;
    }

    private static String traceId() {
        return RfSecurityConfig.traceId(
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes());
    }

    /** S9 所有权保留登记（C-04：默认 REGISTERED；CONTRACTUAL_ONLY 保留配置位）。 */
    @RequirePermission(Permission.TITLE_REGISTER)
    @PostMapping("/api/title-retentions")
    public ApiResponse<TitleRetentionView> register(@RequestBody RegisterRequest req) {
        return ApiResponse.ok(
                service.registerRetention(req.loanId(), req.vin(), req.contractualOnly(), traceId()),
                traceId());
    }

    @GetMapping("/api/title-retentions")
    public ApiResponse<TitleRetentionView> get(@RequestParam long loanId) {
        return ApiResponse.ok(service.getRetention(loanId), traceId());
    }

    public record RegisterRequest(long loanId, String vin, boolean contractualOnly) {
    }
}
