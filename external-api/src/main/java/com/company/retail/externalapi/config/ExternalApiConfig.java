package com.company.retail.externalapi.config;

import com.company.retail.common.config.TraceIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DMZ 装配：traceId 贯穿（复用 common 的 TraceIdFilter；DMZ 不注册 JWT 拦截器——
 * partner 通道按 R07 §5 用 HMAC 验签，不用内部 JWT）。
 */
@Configuration
public class ExternalApiConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> reg = new FilterRegistrationBean<>(new TraceIdFilter());
        reg.addUrlPatterns("/external/*");
        reg.setOrder(1);
        return reg;
    }
}
