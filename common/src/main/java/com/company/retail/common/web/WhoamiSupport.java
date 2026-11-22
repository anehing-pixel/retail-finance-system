package com.company.retail.common.web;

import com.company.platform.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 骨架自证端点的共享实现：{@code GET /api/health}（免鉴权）与
 * {@code GET /api/whoami}（过 JWT 三道关）。
 *
 * <p>「不建只有启动类的空服务」（R02 §3.2）：每个服务的最小可验证能力就是
 * 「走网关 + 过拦截器 + 统一响应 + traceId 贯穿」这条链路，whoami 把验签结果回显出来，
 * 冒烟据此断言认证/主体隔离真实生效，而不是「进程起来了就算通」。</p>
 */
public final class WhoamiSupport {

    private WhoamiSupport() {
    }

    /** GET /api/health 的响应体：服务名 + UP + traceId。 */
    public static ApiResponse<Map<String, Object>> health(String serviceName) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", serviceName);
        data.put("status", "UP");
        return ApiResponse.ok(data, traceId());
    }

    /** GET /api/whoami 的响应体：验签通过后的主体信息（由拦截器放入请求属性）。 */
    public static ApiResponse<Map<String, Object>> whoami(String serviceName) {
        HttpServletRequest request = currentRequest();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", serviceName);
        data.put("subject", request.getAttribute("authSubject"));
        data.put("subjectType", String.valueOf(request.getAttribute("authSubjectType")));
        data.put("roles", request.getAttribute("authRoles"));
        data.put("traceId", traceId());
        return ApiResponse.ok(data, traceId());
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes servletAttrs ? servletAttrs.getRequest() : null;
    }

    private static String traceId() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        Object v = attrs == null ? null : attrs.getAttribute(
                com.company.retail.common.config.TraceIdFilter.ATTR, RequestAttributes.SCOPE_REQUEST);
        return v == null ? "unknown" : String.valueOf(v);
    }
}
