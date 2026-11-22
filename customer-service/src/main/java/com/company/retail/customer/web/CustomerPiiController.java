package com.company.retail.customer.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.retail.customer.app.CustomerPiiData;
import com.company.retail.customer.app.CustomerPiiService;
import com.company.retail.customer.app.IdentityVerificationService;
import com.company.retail.domain.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 客户注册 + PII 视图端点（R09 §3 customer-service 行）。
 * 默认脱敏；全量 {@code PII_VIEW_FULL} + 用途 + 留痕（R08 §5 PII-6）。
 */
@RestController
public class CustomerPiiController {

    private final CustomerPiiService piiService;
    private final IdentityVerificationService verificationService;

    public CustomerPiiController(CustomerPiiService piiService,
                                 IdentityVerificationService verificationService) {
        this.piiService = piiService;
        this.verificationService = verificationService;
    }

    public record RegisterRequest(String externalRef, String name, String nameKana, String address,
                                  String birthDate, String phone, String employer, String annualIncome) {
    }

    /** 客户注册（受理岗，权限点 IDENTITY_VERIFY）。 */
    @PostMapping("/api/customers")
    @RequirePermission(Permission.IDENTITY_VERIFY)
    public ApiResponse<Map<String, Object>> register(@RequestBody RegisterRequest req,
                                                     HttpServletRequest request) {
        CustomerPiiData data = new CustomerPiiData(req.externalRef(), req.name(), req.nameKana(),
                req.address(), req.birthDate() == null ? null : LocalDate.parse(req.birthDate()),
                req.phone(), req.employer(), req.annualIncome());
        long customerId = piiService.register(data, "PORTAL", WebAuthSupport.traceId(request));
        return ApiResponse.ok(Map.of("customerId", customerId), WebAuthSupport.traceId(request));
    }

    /** 脱敏视图（读接口不设权限点，但访问留痕，R08 §3/§5）。 */
    @GetMapping("/api/pii/customers/{id}")
    public ApiResponse<Map<String, String>> masked(@PathVariable("id") long customerId,
                                                   @RequestParam(value = "purpose", required = false) String purpose,
                                                   HttpServletRequest request) {
        Map<String, String> masked = piiService.maskedView(customerId,
                WebAuthSupport.actor(request), WebAuthSupport.subjectType(request),
                purpose, WebAuthSupport.traceId(request));
        return ApiResponse.ok(masked, WebAuthSupport.traceId(request));
    }

    /** 全量视图：PII_VIEW_FULL + 用途 + pii_access_logs 留痕（R08 §5 PII-6）。 */
    @GetMapping("/api/pii/customers/{id}/full")
    @RequirePermission(Permission.PII_VIEW_FULL)
    public ApiResponse<Map<String, Object>> full(@PathVariable("id") long customerId,
                                                 @RequestParam("purpose") String purpose,
                                                 HttpServletRequest request) {
        CustomerPiiData data = piiService.fullView(customerId,
                WebAuthSupport.actor(request), WebAuthSupport.subjectType(request),
                purpose, WebAuthSupport.traceId(request));
        Map<String, Object> plain = new java.util.LinkedHashMap<>();
        plain.put("customerId", customerId);
        plain.put("name", data.name());
        plain.put("nameKana", data.nameKana());
        plain.put("address", data.address());
        plain.put("birthDate", data.birthDate() == null ? null : data.birthDate().toString());
        plain.put("phone", data.phone());
        plain.put("employer", data.employer());
        plain.put("annualIncome", data.annualIncome());
        return ApiResponse.ok(plain, WebAuthSupport.traceId(request));
    }
}
