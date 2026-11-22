package com.company.retail.recon.web;

import com.company.platform.exception.BusinessException;
import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.domain.model.Permission;
import com.company.retail.recon.application.ReconRunAppService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对拍端点（R09 §3：POST /api/recon/run · GET /api/recon/diffs，只读 + 审计）。
 *
 * <p>run 触发批处理 → JOB_TRIGGER（R00 §3.7 语义收口）；diffs / runs 只读不设权限点。</p>
 */
@RestController
@RequestMapping("/api/recon")
public class ReconController {

    private final ReconRunAppService appService;

    public ReconController(ReconRunAppService appService) {
        this.appService = appService;
    }

    /** 执行一轮全视点对拍（INV-1~5；同日重跑幂等，batch_runs 复用同一行）。 */
    @PostMapping("/run")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<Map<String, Object>> run(@RequestBody(required = false) JsonNode body) {
        String bizDate = body == null || body.path("bizDate").asText(null) == null
                ? LocalDate.now().toString()
                : body.path("bizDate").asText();
        String traceId = traceId();
        ReconRunAppService.RunSummary summary = appService.run(bizDate, traceId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobName", ReconRunAppService.JOB_NAME);
        data.put("bizDate", summary.bizDate());
        data.put("findings", summary.findings());
        data.put("openDiffs", summary.openDiffs());
        data.put("resolvedDiffs", summary.resolvedDiffs());
        return ApiResponse.ok(data, traceId);
    }

    /** 不一致清单查询（可按视点 / 状态过滤）。 */
    @GetMapping("/diffs")
    public ApiResponse<Object> diffs(@RequestParam(required = false) String invCode,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) Long diffId) {
        if (invCode != null && !invCode.isBlank() && !invCode.matches("INV-[1-5]")) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    Map.of("reason", "INVALID_INV_CODE", "invCode", invCode));
        }
        if (diffId != null) {
            var row = appService.diff(diffId)
                    .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                            Map.of("reason", "DIFF_NOT_FOUND", "diffId", String.valueOf(diffId))));
            return ApiResponse.ok(view(row), traceId());
        }
        var data = appService.diffs(invCode, status).stream().map(ReconController::view).toList();
        return ApiResponse.ok(data, traceId());
    }

    /** 执行留痕查询（batch_runs）。 */
    @GetMapping("/runs")
    public ApiResponse<Object> runs() {
        var data = appService.runs().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", r.runId());
            m.put("jobName", r.jobName());
            m.put("bizDate", r.bizDate());
            m.put("status", r.status());
            return m;
        }).toList();
        return ApiResponse.ok(data, traceId());
    }

    private static Map<String, Object> view(com.company.retail.recon.application.ReconDiffRepository.DiffRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("diffId", row.diffId());
        m.put("jobName", row.jobName());
        m.put("bizDate", row.bizDate());
        m.put("invCode", row.invCode());
        m.put("entityType", row.entityType());
        m.put("entityId", row.entityId());
        m.put("detail", row.detail());
        m.put("status", row.status());
        return m;
    }

    private static String traceId() {
        return RfSecurityConfig.traceId(RequestContextHolder.currentRequestAttributes());
    }
}
