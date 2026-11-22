package com.company.retail.common.security;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 调用的 JWT 与 traceId 透传（R02 §3.1 范式 3 / R09 §1）。
 *
 * <p><b>为什么必须有</b>：门禁是零信任链路——下游服务本地验签，
 * 调用方若不透传 {@code Authorization}，门禁必 401；floorplan 曾出现
 * 「契约写了但没透传」导致门禁走 fallback 的真实教训。
 * {@code X-Request-Id} 同理：跨服务排障靠它串起日志/审计/outbox。</p>
 *
 * <p>取值来源是当前 HTTP 请求上下文：outbox 消费线程没有请求上下文
 * （消费铁律 3：不许在消费上下文发 Feign），本拦截器在无上下文时透传空——
 * 门禁契约只应在 HTTP 请求线程内使用。</p>
 */
public class JwtPassthroughInterceptor implements RequestInterceptor {

    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    @Override
    public void apply(RequestTemplate template) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && !authorization.isBlank()) {
            template.header("Authorization", authorization);
        }
        String requestId = request.getHeader(HEADER_REQUEST_ID);
        if (requestId != null && !requestId.isBlank()) {
            template.header(HEADER_REQUEST_ID, requestId);
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}
