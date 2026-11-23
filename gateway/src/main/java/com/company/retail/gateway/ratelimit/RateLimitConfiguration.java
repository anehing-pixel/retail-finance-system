package com.company.retail.gateway.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关限流装配（精确装配，不做全包扫描）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

    @Bean
    public ClientIpResolver clientIpResolver(RateLimitProperties properties) {
        return new ClientIpResolver(properties.getTrustedProxyCidrs());
    }

    @Bean
    public RateLimitPolicyResolver rateLimitPolicyResolver(RateLimitProperties properties) {
        return new RateLimitPolicyResolver(properties);
    }

    @Bean(destroyMethod = "shutdown")
    public RateLimitRegistry rateLimitRegistry(RateLimitProperties properties) {
        return new RateLimitRegistry(properties.getMaxTrackedKeys(), properties.getIdleEvictionSeconds());
    }
}
