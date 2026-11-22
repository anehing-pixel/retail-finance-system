package com.company.retail.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * traceId 贯穿（R09 §1 / R02 §9 可观测性）：网关生成 {@code X-Request-Id}，
 * 各服务读取（无则自兜底生成）放进 MDC 与请求属性 {@code traceId}，
 * 供日志、审计、{@code ApiResponse.traceId}、outbox payload 统一取用；响应头回显。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTR = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put(ATTR, traceId);
        request.setAttribute(ATTR, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(ATTR);
        }
    }
}
