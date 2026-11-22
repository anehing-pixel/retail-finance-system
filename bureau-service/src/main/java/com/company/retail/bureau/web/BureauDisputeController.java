package com.company.retail.bureau.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.retail.bureau.application.BureauDisputeAppService;
import com.company.retail.bureau.application.BureauDisputeRepository;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.domain.model.Permission;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 征信异议端点（R09 §3：GET / POST 处理，BUREAU_DISPUTE_HANDLE；SLA 30 日，C-05）。
 */
@RestController
@RequestMapping("/api/bureau-disputes")
public class BureauDisputeController {

    private final BureauDisputeAppService appService;
    private final BureauDisputeRepository disputeRepository;

    public BureauDisputeController(BureauDisputeAppService appService,
                                   BureauDisputeRepository disputeRepository) {
        this.appService = appService;
        this.disputeRepository = disputeRepository;
    }

    /** 受理异议工单（due_at = 受理 + SLA；customer_ref 只存引用码）。 */
    @PostMapping
    @RequirePermission(Permission.BUREAU_DISPUTE_HANDLE)
    public ApiResponse<Map<String, Object>> create(@RequestBody JsonNode body) {
        var view = appService.open(body.path("customerRef").asText(null),
                body.path("bureau").asText(null),
                body.path("type").asText(null),
                traceId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("disputeId", view.disputeId());
        data.put("bureau", view.bureau());
        data.put("type", view.type());
        data.put("dueAt", view.dueAt());
        data.put("status", view.status());
        return ApiResponse.ok(data, traceId());
    }

    /** 处理异议（订正报送经 ACL；超期 → RF_BUREAU_002 合规升级）。 */
    @PostMapping("/{id}/handle")
    @RequirePermission(Permission.BUREAU_DISPUTE_HANDLE)
    public ApiResponse<Map<String, Object>> handle(@PathVariable long id, @RequestBody JsonNode body) {
        var view = appService.handle(id, body.path("resolutionRef").asText(null), traceId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("disputeId", view.disputeId());
        data.put("bureau", view.bureau());
        data.put("type", view.type());
        data.put("dueAt", view.dueAt());
        data.put("status", view.status());
        return ApiResponse.ok(data, traceId());
    }

    /** 工单查询（读，不设权限点；customer_ref 不回显）。 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable long id) {
        var row = disputeRepository.findById(id)
                .orElseThrow(() -> new com.company.platform.exception.BusinessException(
                        RFErrorCode.RF_VALID_001, Map.of("reason", "DISPUTE_NOT_FOUND", "disputeId", String.valueOf(id))));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("disputeId", row.disputeId());
        data.put("bureau", row.bureau());
        data.put("type", row.type());
        data.put("dueAt", row.dueAt() == null ? null : row.dueAt().toString());
        data.put("status", row.status());
        return ApiResponse.ok(data, traceId());
    }

    private static String traceId() {
        return RfSecurityConfig.traceId(RequestContextHolder.currentRequestAttributes());
    }
}
