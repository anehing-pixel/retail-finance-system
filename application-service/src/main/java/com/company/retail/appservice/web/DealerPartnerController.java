package com.company.retail.appservice.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.retail.appservice.app.DealerProjectionService;
import com.company.retail.domain.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 销售店主数据端点（R09 §3：GET / POST 维护 + 同步入口；权限 DEALER_MAINTAIN）。
 * 投影只读（ADR-RF-010）：POST /sync 是主数据投影的挡板推送入口，不做任何业务维护写。
 */
@RestController
public class DealerPartnerController {

    private final DealerProjectionService service;

    public DealerPartnerController(DealerProjectionService service) {
        this.service = service;
    }

    @GetMapping("/api/dealer-partners")
    public ApiResponse<Map<String, Object>> list() {
        return ApiResponse.ok(Map.of("items", service.list()), null);
    }

    public record SyncRequest(String dealerMasterRef, String nameSnapshot, String status,
                              Boolean simulateFailure) {
    }

    /** 挡板同步入口（floorplan → 投影；失败降级 UNSYNCED 人工）。 */
    @PostMapping("/api/dealer-partners/sync")
    @RequirePermission(Permission.DEALER_MAINTAIN)
    public ApiResponse<Map<String, Object>> sync(@RequestBody SyncRequest req, HttpServletRequest request) {
        Map<String, Object> result = service.sync(req.dealerMasterRef(), req.nameSnapshot(),
                req.status(), Boolean.TRUE.equals(req.simulateFailure()), WebAuthSupport.traceId(request));
        return ApiResponse.ok(result, WebAuthSupport.traceId(request));
    }
}
