package com.company.retail.servicing.web;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * web 层验签身份解析（B-9：审计 actor 取验签身份，禁止取自请求体或客户端头）。
 *
 * <p>属性由中台 {@code PlatformJwtAuthInterceptor} 验签后放入请求上下文；
 * 无请求上下文（outbox 消费/调度）时按 SYSTEM 处理。</p>
 */
public final class WebSubjects {

    private WebSubjects() {
    }

    public static String actor() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "SYSTEM";
        }
        Object subject = attrs.getAttribute("authSubject", RequestAttributes.SCOPE_REQUEST);
        return subject == null || String.valueOf(subject).isBlank()
                ? "SYSTEM" : String.valueOf(subject);
    }

    public static String subjectType() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "SYSTEM";
        }
        Object type = attrs.getAttribute("authSubjectType", RequestAttributes.SCOPE_REQUEST);
        return type == null ? "SYSTEM" : String.valueOf(type);
    }
}
