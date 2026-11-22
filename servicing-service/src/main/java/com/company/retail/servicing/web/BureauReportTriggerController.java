package com.company.retail.servicing.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.domain.model.Permission;
import com.company.retail.servicing.application.BureauReportTriggerAppService;
import com.company.retail.servicing.application.BureauReportTriggerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 征信报送触发端点（W7：触发登记与状态跟踪；执行方是 bureau-service）。
 *
 * <p>登记用 BUREAU_REPORT_EXEC（R00 §3.7 语义收口）；查询为只读不设权限点。</p>
 */
@RestController
@RequestMapping("/api/bureau-report-triggers")
public class BureauReportTriggerController {

    private final BureauReportTriggerAppService appService;

    public BureauReportTriggerController(BureauReportTriggerAppService appService) {
        this.appService = appService;
    }

    /** 登记逾期报送触发（T+1 营业日；INV-5 fail-closed 同意门禁）。 */
    @PostMapping
    @RequirePermission(Permission.BUREAU_REPORT_EXEC)
    public ApiResponse<Map<String, Object>> register(@RequestBody JsonNode body) {
        var row = appService.register(
                body.path("loanId").asLong(0),
                body.path("consentId").asLong(0),
                body.path("dpdStage").asText(null),
                WebSubjects.actor(), traceId());
        return ApiResponse.ok(view(row), traceId());
    }

    @GetMapping
    public ApiResponse<Object> list(@RequestParam(required = false) String status) {
        var data = appService.list(status).stream()
                .map(BureauReportTriggerController::view).toList();
        return ApiResponse.ok(data, traceId());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable long id) {
        var row = appService.get(id).orElseThrow(() ->
                new com.company.platform.exception.BusinessException(
                        com.company.retail.domain.exception.RFErrorCode.RF_VALID_001,
                        Map.of("reason", "TRIGGER_NOT_FOUND", "triggerId", String.valueOf(id))));
        return ApiResponse.ok(view(row), traceId());
    }

    /** 状态跟踪（含 outbox DPD_CHANGED 送达锚点）。 */
    @GetMapping("/{id}/track")
    public ApiResponse<Map<String, Object>> track(@PathVariable long id) {
        return ApiResponse.ok(appService.track(id, traceId()), traceId());
    }

    private static Map<String, Object> view(BureauReportTriggerRepository.TriggerRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("triggerId", row.triggerId());
        m.put("loanId", row.loanId());
        m.put("dpdStage", row.dpdStage());
        m.put("triggerType", row.triggerType());
        m.put("targetBizDate", row.targetBizDate().toString());
        m.put("status", row.status());
        return m;
    }

    private static String traceId() {
        RequestAttributes attrs = RequestContextHolder.currentRequestAttributes();
        return RfSecurityConfig.traceId(attrs);
    }
}
