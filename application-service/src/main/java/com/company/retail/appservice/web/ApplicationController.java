package com.company.retail.appservice.web;

import com.company.platform.idempotency.IdempotencyGuard;
import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.retail.appservice.app.ApplicationService;
import com.company.retail.domain.model.Permission;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 申请端点（R09 §3：POST 受理 / GET 查询 / POST 补正；权限 APPLICATION_REVIEW）。
 * 幂等：Idempotency-Key 头 + DB 唯一索引双兜底（R09 §2）。
 */
@RestController
public class ApplicationController {

    private final ApplicationService service;
    private final ObjectMapper mapper;

    public ApplicationController(ApplicationService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public record SubmitRequest(long customerId, Long dealerPartnerId, String transactionScheme,
                                String channel, Long consentId,
                                String vin, String model, long cashPrice, long accessoriesAmount) {
    }

    /** 受理申请（DRAFT→SUBMITTED 语义的提交入口；成功发 APPLICATION_SUBMITTED）。 */
    @PostMapping("/api/applications")
    @RequirePermission(Permission.APPLICATION_REVIEW)
    public ApiResponse<Map<String, Object>> submit(@RequestBody SubmitRequest req,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                   HttpServletRequest request) {
        ApplicationService.SubmitCommand cmd = new ApplicationService.SubmitCommand(
                req.customerId(), req.dealerPartnerId(), req.transactionScheme(), req.channel(),
                req.consentId(), new com.company.retail.appservice.domain.Application.Item(
                req.vin(), req.model(), req.cashPrice(), req.accessoriesAmount()));
        // 指纹：规范化请求内容（排序后的 JSON）→ SHA-256；时间戳等易变字段不混入
        String fingerprint = IdempotencyGuard.sha256Fingerprint(canonicalJson(req));
        ApplicationService.Result result = service.submit(cmd, idempotencyKey, fingerprint,
                WebAuthSupport.traceId(request));
        return ApiResponse.ok(Map.of(
                        "applicationId", result.applicationId(),
                        "applicationNo", result.applicationNo() == null ? "" : result.applicationNo(),
                        "status", result.status().name(),
                        "replayed", result.replayed()),
                WebAuthSupport.traceId(request));
    }

    /** 撤回（SUBMITTED → WITHDRAWN 终态）。 */
    @PostMapping("/api/applications/{id}/withdraw")
    @RequirePermission(Permission.APPLICATION_REVIEW)
    public ApiResponse<Void> withdraw(@PathVariable("id") long applicationId, HttpServletRequest request) {
        service.withdraw(applicationId, WebAuthSupport.traceId(request));
        return ApiResponse.ok(null, WebAuthSupport.traceId(request));
    }

    /** 系统初检转补件（SUBMITTED → DOCUMENTS_PENDING）。 */
    @PostMapping("/api/applications/{id}/documents")
    @RequirePermission(Permission.APPLICATION_REVIEW)
    public ApiResponse<Void> requestDocuments(@PathVariable("id") long applicationId,
                                              HttpServletRequest request) {
        service.requestDocuments(applicationId, WebAuthSupport.traceId(request));
        return ApiResponse.ok(null, WebAuthSupport.traceId(request));
    }

    @GetMapping("/api/applications/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable("id") long applicationId) {
        return ApiResponse.ok(service.get(applicationId), null);
    }

    /** 键排序后的规范化 JSON（幂等指纹输入，确定性由这里保证）。 */
    private String canonicalJson(Object req) {
        try {
            Map<String, Object> sorted = new java.util.TreeMap<>(
                    mapper.readValue(mapper.writeValueAsString(req), Map.class));
            return mapper.writeValueAsString(sorted);
        } catch (Exception e) {
            throw new IllegalStateException("请求指纹规范化失败", e);
        }
    }
}
