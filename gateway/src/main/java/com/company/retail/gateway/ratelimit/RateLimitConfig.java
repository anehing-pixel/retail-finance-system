package com.company.retail.gateway.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关限流装配（参数绑定 + 规则匹配器）。
 *
 * <p>网关是独立部署单元，不依赖 common；限流件全部落在网关自己的包内。</p>
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    public RateLimitRuleMatcher rateLimitRuleMatcher(RateLimitProperties props) {
        return new RateLimitRuleMatcher(props.getRules());
    }
}
