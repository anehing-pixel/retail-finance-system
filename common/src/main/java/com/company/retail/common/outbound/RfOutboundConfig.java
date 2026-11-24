package com.company.retail.common.outbound;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 出站韧性装配（P0-7；R02 §7.4）。
 *
 * <p>与 {@code RfCacheConfig} / {@code RfLockConfig} 同款精确装配：不进全包扫描，
 * 由各服务启动类 {@code @Import} 按类注入。</p>
 */
@Configuration
@EnableConfigurationProperties(OutboundProperties.class)
public class RfOutboundConfig {

    @Bean
    public OutboundCircuitBreakerRegistry outboundCircuitBreakerRegistry() {
        return new OutboundCircuitBreakerRegistry();
    }

    @Bean
    public OutboundHttpClientFactory outboundHttpClientFactory(OutboundProperties properties,
                                                               OutboundCircuitBreakerRegistry registry) {
        return new OutboundHttpClientFactory(properties, registry);
    }
}
