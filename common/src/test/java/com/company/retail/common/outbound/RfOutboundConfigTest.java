package com.company.retail.common.outbound;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-7：共享装配类在真实上下文中暴露工厂与属性（精确装配，不靠全包扫描）。
 */
class RfOutboundConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RfOutboundConfig.class);

    @Test
    void exposesFactoryAndProperties() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(OutboundHttpClientFactory.class);
            assertThat(context).hasSingleBean(OutboundCircuitBreakerRegistry.class);
            assertThat(context.getBean(OutboundProperties.class).isEnabled()).isTrue();
            assertThat(context.getBean(OutboundHttpClientFactory.class)
                    .client("gate", OutboundProfile.GATE)).isNotNull();
        });
    }

    @Test
    void configPropertiesCanBeOverridden() {
        runner.withPropertyValues(
                        "retail.outbound.enabled=false",
                        "retail.outbound.gate.read-timeout=3s")
                .run(context -> {
                    OutboundProperties props = context.getBean(OutboundProperties.class);
                    assertThat(props.isEnabled()).isFalse();
                    assertThat(props.getGate().getReadTimeout()).isEqualTo(java.time.Duration.ofSeconds(3));
                });
    }
}
