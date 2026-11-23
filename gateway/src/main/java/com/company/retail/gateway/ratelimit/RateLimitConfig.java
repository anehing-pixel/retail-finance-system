package com.company.retail.gateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关限流装配（Redis 令牌桶模式：参数绑定 + 规则匹配器 + 过滤器）。
 *
 * <p><b>只在 {@code retail.gateway.rate-limit.mode=redis}（缺省）时装配</b>；
 * 与进程内模式的 {@link InMemoryRateLimitConfig} 互斥——两个配置类各自带互补的
 * {@code @ConditionalOnProperty}，因此同一时刻只会注册一个限流过滤器，
 * 不会出现双重限流。开关口径见 {@code R02 §3.3}。</p>
 *
 * <p>网关是独立部署单元，不依赖 common；限流件全部落在网关自己的包内。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "retail.gateway.rate-limit", name = "mode",
        havingValue = "redis", matchIfMissing = true)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    public RateLimitRuleMatcher rateLimitRuleMatcher(RateLimitProperties props) {
        return new RateLimitRuleMatcher(props.getRules());
    }

    @Bean
    public RateLimitFilter rateLimitFilter(RateLimitProperties props,
                                            RateLimitRuleMatcher matcher,
                                            TokenBucketLimiter limiter,
                                            ObjectMapper mapper) {
        return new RateLimitFilter(props, matcher, limiter, mapper);
    }
}
