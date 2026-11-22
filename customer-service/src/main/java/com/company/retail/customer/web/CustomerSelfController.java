package com.company.retail.customer.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequireSubject;
import com.company.platform.security.SubjectType;
import com.company.retail.customer.app.CustomerPiiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 客户自助端点（挂 {@code /api/customer-self/**}，由 common 双拦截器机制用
 * 客户侧验签器拦截——S2 遗留收口：客户 token 与员工 token 路径互斥，双主体隔离）。
 *
 * <p>token subject 即客户 ID（auth-service 客户开发桥按 customerId 签发；
 * 真实客户认证交付后口径不变）。只返回本人脱敏视图，永远不给全量（R09 §2 默认脱敏）。</p>
 */
@RestController
@RequireSubject(SubjectType.CUSTOMER)
public class CustomerSelfController {

    private final CustomerPiiService piiService;

    public CustomerSelfController(CustomerPiiService piiService) {
        this.piiService = piiService;
    }

    /** 客户查看本人脱敏信息：GET /api/customer-self/me。 */
    @GetMapping("/api/customer-self/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        long customerId;
        try {
            customerId = Long.parseLong(WebAuthSupport.actor(request));
        } catch (NumberFormatException e) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_AUTH_001,
                    Map.of("reason", "客户 token subject 不是客户 ID"));
        }
        Map<String, String> masked = piiService.maskedView(
                customerId, WebAuthSupport.actor(request), WebAuthSupport.subjectType(request),
                "CUSTOMER_SELF_VIEW", WebAuthSupport.traceId(request));
        return ApiResponse.ok(Map.of(
                "customerId", customerId,
                "pii", masked,
                "notice", "默认脱敏视图；如需更正个人信息请通过店头或客服申请"),
                WebAuthSupport.traceId(request));
    }
}
