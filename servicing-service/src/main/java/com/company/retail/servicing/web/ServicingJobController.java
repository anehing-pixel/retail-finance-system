package com.company.retail.servicing.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.domain.model.Permission;
import com.company.retail.servicing.application.DpdBatchAppService;
import com.company.retail.servicing.application.DpdSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DPD 批处理作业端点（R09 §3：各服务 /api/jobs/{jobName} POST 触发，JOB_TRIGGER）。
 */
@RestController
public class ServicingJobController {

    private final DpdBatchAppService dpdBatchAppService;
    private final DpdSnapshotRepository snapshotRepository;

    public ServicingJobController(DpdBatchAppService dpdBatchAppService,
                                  DpdSnapshotRepository snapshotRepository) {
        this.dpdBatchAppService = dpdBatchAppService;
        this.snapshotRepository = snapshotRepository;
    }

    /** 手动触发 DPD 快照（默认今日；同日重跑幂等）。 */
    @PostMapping("/api/jobs/dpd-snapshot")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<Map<String, Object>> run(@RequestBody(required = false) JsonNode body) {
        LocalDate bizDate = body == null || body.path("bizDate").asText(null) == null
                ? LocalDate.now()
                : LocalDate.parse(body.path("bizDate").asText());
        var summary = dpdBatchAppService.run(bizDate, WebSubjects.actor(), traceId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobName", DpdBatchAppService.JOB_NAME);
        data.put("bizDate", summary.bizDate().toString());
        data.put("loanCount", summary.loanCount());
        data.put("snapshotCount", summary.snapshotCount());
        data.put("eventCount", summary.eventCount());
        data.put("casesOpened", summary.casesOpened());
        return ApiResponse.ok(data, traceId());
    }

    /** DPD 快照查询（只读）。 */
    @GetMapping("/api/dpd-snapshots")
    public ApiResponse<Object> snapshots(@RequestParam(required = false) Long loanId) {
        if (loanId == null) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_VALID_001,
                    Map.of("reason", "LOAN_ID_REQUIRED"));
        }
        var data = snapshotRepository.listByLoan(loanId).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("snapshotId", s.snapshotId());
            m.put("loanId", s.loanId());
            m.put("bizDate", s.bizDate().toString());
            m.put("dpdDays", s.dpdDays());
            m.put("stage", s.stage());
            m.put("dayBasis", s.dayBasis());
            return m;
        }).toList();
        return ApiResponse.ok(data, traceId());
    }

    private static String traceId() {
        return RfSecurityConfig.traceId(
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes());
    }
}
