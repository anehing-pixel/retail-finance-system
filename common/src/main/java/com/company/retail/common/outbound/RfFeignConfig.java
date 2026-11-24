package com.company.retail.common.outbound;

import feign.Request;
import feign.Retryer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FeignBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Feign 出站韧性装配（P0-7；R02 §7.4）。
 *
 * <p>用官方扩展点 {@link FeignBuilderCustomizer}（最后应用）设置显式超时/重试/熔断；
 * 不新增 {@code Request.Options} bean（Spring Cloud OpenFeign 4.1 不会从父上下文取它）。</p>
 */
@Configuration
@Import(RfOutboundConfig.class)
public class RfFeignConfig {

    private static final Logger log = LoggerFactory.getLogger(RfFeignConfig.class);

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public FeignBuilderCustomizer rfFeignOutboundCustomizer(OutboundProperties properties,
                                                           OutboundCircuitBreakerRegistry registry) {
        return builder -> {
            OutboundPolicy gate = properties.policyOf(OutboundProfile.GATE);
            builder.options(new Request.Options(gate.getConnectTimeout(), gate.getReadTimeout(), true));
            log.info("出站 Feign 门禁策略生效 connect={} read={} enabled={} circuit={}",
                    gate.getConnectTimeout(), gate.getReadTimeout(),
                    properties.isEnabled(), properties.isCircuitEnabled());
            if (!properties.isEnabled()) {
                builder.retryer(Retryer.NEVER_RETRY);
                return;
            }
            builder.retryer(new RfFeignRetryer(gate.getMaxAttempts(), gate.getRetryBackoff()));
            if (properties.isCircuitEnabled()) {
                builder.addCapability(new CircuitBreakerCapability(registry, gate));
            }
        };
    }
}
