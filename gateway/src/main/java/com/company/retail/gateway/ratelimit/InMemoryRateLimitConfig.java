package com.company.retail.gateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关限流装配（<b>进程内模式</b>）。
 *
 * <p><b>只在 {@code retail.gateway.rate-limit.mode=memory} 时装配</b>：与 Redis 模式的
 * {@link RateLimitConfig} 互斥——两个配置类各自带互补的 {@code @ConditionalOnProperty}，
 * 因此同一时刻只会注册一个限流过滤器，不会出现双重限流。
 * 开关口径见 {@code R02 §3.3}。</p>
 *
 * <p>网关是独立部署单元，不依赖 common；限流件全部落在网关自己的包内。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "retail.gateway.rate-limit", name = "mode", havingValue = "memory")
@EnableConfigurationProperties(InMemoryRateLimitProperties.class)
public class InMemoryRateLimitConfig {

    @Bean
    public ClientIpResolver clientIpResolver(InMemoryRateLimitProperties properties) {
        return new ClientIpResolver(properties.getTrustedProxyCidrs());
    }

    @Bean
    public RateLimitPolicyResolver rateLimitPolicyResolver(InMemoryRateLimitProperties properties) {
        return new RateLimitPolicyResolver(properties);
    }

    @Bean(destroyMethod = "shutdown")
    public RateLimitRegistry rateLimitRegistry(InMemoryRateLimitProperties properties) {
        return new RateLimitRegistry(properties.getMaxTrackedKeys(), properties.getIdleEvictionSeconds());
    }

    @Bean
    public InMemoryRateLimitFilter inMemoryRateLimitFilter(InMemoryRateLimitProperties properties,
                                                            RateLimitPolicyResolver policyResolver,
                                                            ClientIpResolver clientIpResolver,
                                                            RateLimitRegistry registry,
                                                            ObjectMapper objectMapper) {
        return new InMemoryRateLimitFilter(properties, policyResolver, clientIpResolver, registry, objectMapper);
    }
}
