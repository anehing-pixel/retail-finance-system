package com.company.retail.appservice.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Web 层身份/traceId 取值辅助（属性由 common 的拦截器与 TraceIdFilter 放入）。
 */
public final class WebAuthSupport {

    private WebAuthSupport() {
    }

    public static String actor(HttpServletRequest request) {
        Object v = request.getAttribute("authSubject");
        return v == null ? "unknown" : String.valueOf(v);
    }

    public static String subjectType(HttpServletRequest request) {
        Object v = request.getAttribute("authSubjectType");
        return v == null ? "UNKNOWN" : String.valueOf(v);
    }

    public static String traceId(HttpServletRequest request) {
        return com.company.retail.common.config.RfSecurityConfig.traceId(
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes());
    }
}
