package com.company.retail.bureau.web;

import com.company.platform.exception.BusinessException;
import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.retail.bureau.application.BureauReportAppService;
import com.company.retail.bureau.application.BureauMessageLogRepository;
import com.company.retail.bureau.application.BureauReportRepository;
import com.company.retail.bureau.application.ManualReportAppService;
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
 * 征信报送端点（R09 §3：POST /api/bureau-reports 手动重送 / GET，BUREAU_REPORT_EXEC）。
 */
@RestController
@RequestMapping("/api/bureau-reports")
public class BureauReportController {

    private final BureauReportAppService appService;
    private final BureauReportRepository reportRepository;
    private final BureauMessageLogRepository messageLogRepository;
    private final ManualReportAppService manualReportAppService;

    public BureauReportController(BureauReportAppService appService,
                                  BureauReportRepository reportRepository,
                                  BureauMessageLogRepository messageLogRepository,
                                  ManualReportAppService manualReportAppService) {
        this.appService = appService;
        this.reportRepository = reportRepository;
        this.messageLogRepository = messageLogRepository;
        this.manualReportAppService = manualReportAppService;
    }

    /** 发起报送（X-11 人工重送入口之一：新建 trigger 的报送）。 */
    @PostMapping
    @RequirePermission(Permission.BUREAU_REPORT_EXEC)
    public ApiResponse<Map<String, Object>> create(@RequestBody JsonNode body) {
        long loanId = body.path("loanId").asLong(0);
        long consentId = body.path("consentId").asLong(0);
        if (loanId <= 0 || consentId <= 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    Map.of("reason", "LOAN_AND_CONSENT_REQUIRED"));
        }
        var row = appService.sendReport(loanId, consentId,
                body.path("trigger").asText(null), traceId());
        return ApiResponse.ok(view(row), traceId());
    }

    /** 人工重触发（REJECTED/PENDING → 重走挡板报送；SENT 拒绝）。 */
    @PostMapping("/{id}/resend")
    @RequirePermission(Permission.BUREAU_REPORT_EXEC)
    public ApiResponse<Map<String, Object>> resend(@PathVariable long id) {
        return ApiResponse.ok(view(appService.resend(id, traceId())), traceId());
    }

    /**
     * 人工报送请求登记（ADR-RF-005 §5 降级通道：source=MANUAL，PENDING 挂起）。
     * 挡板语义：登记不真调机构；后续经 manual-result 录入机构返回结果。
     */
    @PostMapping("/manual-requests")
    @RequirePermission(Permission.BUREAU_REPORT_EXEC)
    public ApiResponse<Map<String, Object>> registerManualRequest(@RequestBody JsonNode body) {
        long loanId = body.path("loanId").asLong(0);
        long consentId = body.path("consentId").asLong(0);
        if (loanId <= 0 || consentId <= 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    Map.of("reason", "LOAN_AND_CONSENT_REQUIRED"));
        }
        var row = manualReportAppService.registerManualRequest(loanId, consentId,
                body.path("bureau").asText(null), body.path("note").asText(null), traceId());
        return ApiResponse.ok(viewManual(row), traceId());
    }

    /** 人工报送结果录入（挡板：不真调机构；录入报文落 INBOUND 留痕，ACCEPTED → SENT）。 */
    @PostMapping("/{id}/manual-result")
    @RequirePermission(Permission.BUREAU_REPORT_EXEC)
    public ApiResponse<Map<String, Object>> recordManualResult(@PathVariable long id,
                                                               @RequestBody JsonNode body) {
        var row = manualReportAppService.recordManualResult(id,
                body.path("rawMessage").asText(null),
                body.path("responseRef").asText(null),
                body.path("outcome").asText("ACCEPTED"), traceId());
        return ApiResponse.ok(viewManual(row), traceId());
    }

    /** 报送留痕查询（读，不设权限点；报文原文不在此暴露）。 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable long id) {
        var row = reportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        Map.of("reason", "REPORT_NOT_FOUND", "reportId", String.valueOf(id))));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reportId", row.reportId());
        data.put("loanId", row.loanId());
        data.put("bureau", row.bureau());
        data.put("trigger", row.trigger());
        data.put("status", row.status());
        data.put("messageLogCount", messageLogRepository.countByBizRef(String.valueOf(id)));
        return ApiResponse.ok(data, traceId());
    }

    private static Map<String, Object> view(BureauReportAppService.ReportRow row) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reportId", row.reportId());
        data.put("loanId", row.loanId());
        data.put("bureau", row.bureau());
        data.put("trigger", row.trigger());
        data.put("status", row.status());
        return data;
    }

    private static Map<String, Object> viewManual(BureauReportRepository.ReportRow row) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reportId", row.reportId());
        data.put("loanId", row.loanId());
        data.put("bureau", row.bureau());
        data.put("trigger", row.trigger());
        data.put("status", row.status());
        data.put("source", row.source());
        return data;
    }

    private static String traceId() {
        return RfSecurityConfig.traceId(RequestContextHolder.currentRequestAttributes());
    }
}
