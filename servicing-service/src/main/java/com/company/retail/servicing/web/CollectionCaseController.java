package com.company.retail.servicing.web;

import com.company.platform.exception.BusinessException;
import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.domain.model.Permission;
import com.company.retail.servicing.application.CollectionActionRepository;
import com.company.retail.servicing.application.CollectionCaseAppService;
import com.company.retail.servicing.application.CollectionCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 催收案件端点（R09 §3：/api/collection-cases GET / POST 处置，COLLECTION_HANDLE）。
 *
 * <p>催收必经案件：所有写操作都锚定 caseId——不存在「按客户/债权直接催收」的入口
 * （R08 不可配置旁路）。</p>
 */
@RestController
@RequestMapping("/api/collection-cases")
public class CollectionCaseController {

    private final CollectionCaseAppService appService;

    public CollectionCaseController(CollectionCaseAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    public ApiResponse<Object> list(@RequestParam(required = false) String status) {
        var data = appService.list(status).stream().map(CollectionCaseController::view).toList();
        return ApiResponse.ok(data, traceId());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable long id) {
        var row = appService.get(id)
                .orElseThrow(() -> notFound(id));
        return ApiResponse.ok(view(row), traceId());
    }

    @GetMapping("/{id}/actions")
    public ApiResponse<Object> actions(@PathVariable long id) {
        appService.get(id).orElseThrow(() -> notFound(id));
        var data = appService.actions(id).stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("actionId", a.actionId());
            m.put("caseId", a.caseId());
            m.put("actionType", a.actionType());
            m.put("outcome", a.outcome());
            m.put("note", a.note());
            m.put("actor", a.actor());
            m.put("actorSubject", a.actorSubject());
            return m;
        }).toList();
        return ApiResponse.ok(data, traceId());
    }

    /** 催收动作（分案/联系/承诺/关案；COLLECTION_HANDLE；状态机走聚合方法）。 */
    @PostMapping("/{id}/actions")
    @RequirePermission(Permission.COLLECTION_HANDLE)
    public ApiResponse<Map<String, Object>> execute(@PathVariable long id,
                                                    @RequestBody JsonNode body) {
        CollectionCaseAppService.ActionRow row = appService.executeAction(
                id,
                body.path("actionType").asText(null),
                body.path("assignee").asText(null),
                body.path("outcome").asText(null),
                body.path("note").asText(null),
                WebSubjects.actor(), WebSubjects.subjectType(),
                OffsetDateTime.now(), traceId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseId", row.caseId());
        data.put("actionType", row.actionType());
        data.put("outcome", row.outcome());
        data.put("caseStatus", row.caseStatus());
        return ApiResponse.ok(data, traceId());
    }

    private static Map<String, Object> view(CollectionCaseRepository.CaseRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseId", row.caseId());
        m.put("loanId", row.loanId());
        m.put("dpdStage", row.dpdStage());
        m.put("status", row.status());
        m.put("assignedTo", row.assignedTo());
        m.put("version", row.version());
        return m;
    }

    private static BusinessException notFound(long id) {
        return new BusinessException(RFErrorCode.RF_VALID_001,
                Map.of("reason", "COLLECTION_CASE_NOT_FOUND", "caseId", String.valueOf(id)));
    }

    private static String traceId() {
        return RfSecurityConfig.traceId(RequestContextHolderHolder.attrs());
    }

    /** 小垫片：避免控制器多处重复 RequestContextHolder 样板。 */
    private static final class RequestContextHolderHolder {
        static org.springframework.web.context.request.RequestAttributes attrs() {
            return org.springframework.web.context.request.RequestContextHolder
                    .currentRequestAttributes();
        }
    }
}
