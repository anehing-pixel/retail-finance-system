package com.company.retail.underwriting.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.retail.common.config.TraceIdFilter;
import com.company.retail.domain.model.Permission;
import com.company.retail.underwriting.application.OutboundTaskRetryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 出站任务人工重触发端点（P0-7；R07 §2.3 运维「重新推送」）。
 *
 * <p>权限复用 {@link Permission#JOB_TRIGGER}：这是批处理/运维动作，不新增权限点。</p>
 */
@RestController
public class OutboundTaskRetryController {

    private final OutboundTaskRetryService service;

    public OutboundTaskRetryController(OutboundTaskRetryService service) {
        this.service = service;
    }

    @PostMapping("/api/underwriting/outbound-tasks/{taskId}/retry")
    @RequirePermission(Permission.JOB_TRIGGER)
    public ApiResponse<?> retry(@PathVariable("taskId") long taskId, HttpServletRequest request) {
        String status = service.retry(taskId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("status", status);
        Object traceId = request.getAttribute(TraceIdFilter.ATTR);
        return ApiResponse.ok(data, traceId == null ? UUID.randomUUID().toString()
                : String.valueOf(traceId));
    }
}
