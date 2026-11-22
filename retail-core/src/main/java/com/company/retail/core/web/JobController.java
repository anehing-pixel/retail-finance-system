package com.company.retail.core.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.platform.security.RequireSubject;
import com.company.platform.security.SubjectType;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.core.application.ScheduleDueJobService;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.platform.exception.BusinessException;
import com.company.retail.domain.model.Permission;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 批处理作业触发端点（R09 §3：各服务 /api/jobs/{jobName}，权限点 JOB_TRIGGER）。
 * 本服务实现：schedule-due-rollup（W6 到期批量）。
 */
@RestController
@RequireSubject(SubjectType.EMPLOYEE)
public class JobController {

    private final ScheduleDueJobService dueJobService;

    public JobController(ScheduleDueJobService dueJobService) {
        this.dueJobService = dueJobService;
    }

    private static String traceId() {
        return RfSecurityConfig.traceId(
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes());
    }

    /** body：{"bizDate": "yyyy-MM-dd"}（缺省当天）。 */
    @RequirePermission(Permission.JOB_TRIGGER)
    @PostMapping("/api/jobs/{jobName}")
    public ApiResponse<Map<String, Object>> trigger(@PathVariable String jobName,
                                                    @RequestBody(required = false) JobRequest req) {
        if (!ScheduleDueJobService.JOB_NAME.equals(jobName)) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    "未知作业名（本服务实现：" + ScheduleDueJobService.JOB_NAME + "）",
                    Map.of("jobName", String.valueOf(jobName)));
        }
        LocalDate bizDate;
        try {
            bizDate = req == null || req.bizDate() == null || req.bizDate().isBlank()
                    ? LocalDate.now() : LocalDate.parse(req.bizDate());
        } catch (DateTimeParseException e) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "bizDate 必须是 yyyy-MM-dd");
        }
        return ApiResponse.ok(dueJobService.rollUp(bizDate, traceId()), traceId());
    }

    public record JobRequest(String bizDate) {
    }
}
