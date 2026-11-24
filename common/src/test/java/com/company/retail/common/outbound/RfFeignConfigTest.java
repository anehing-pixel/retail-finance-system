package com.company.retail.common.outbound;

import feign.Feign;
import feign.Request;
import feign.Retryer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.openfeign.FeignBuilderCustomizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P0-7：Feign 显式超时 + 传输层重试 + 熔断装配（不启 Spring）。
 */
class RfFeignConfigTest {

    private final RfFeignConfig config = new RfFeignConfig();

    @Test
    void appliesGateTimeoutsRetryerAndCircuitCapability() {
        OutboundProperties props = new OutboundProperties();
        FeignBuilderCustomizer customizer = config.rfFeignOutboundCustomizer(
                props, new OutboundCircuitBreakerRegistry());
        Feign.Builder builder = mock(Feign.Builder.class, RETURNS_SELF);

        customizer.customize(builder);

        ArgumentCaptor<Request.Options> options = ArgumentCaptor.forClass(Request.Options.class);
        verify(builder).options(options.capture());
        assertEquals(500, options.getValue().connectTimeoutMillis());
        assertEquals(2000, options.getValue().readTimeoutMillis());

        ArgumentCaptor<Retryer> retryer = ArgumentCaptor.forClass(Retryer.class);
        verify(builder).retryer(retryer.capture());
        assertInstanceOf(RfFeignRetryer.class, retryer.getValue());
        verify(builder).addCapability(any(CircuitBreakerCapability.class));
    }

    @Test
    void disabledOutboundFallsBackToNeverRetryAndNoCircuit() {
        OutboundProperties props = new OutboundProperties();
        props.setEnabled(false);
        FeignBuilderCustomizer customizer = config.rfFeignOutboundCustomizer(
                props, new OutboundCircuitBreakerRegistry());
        Feign.Builder builder = mock(Feign.Builder.class, RETURNS_SELF);

        customizer.customize(builder);

        ArgumentCaptor<Retryer> retryer = ArgumentCaptor.forClass(Retryer.class);
        verify(builder).retryer(retryer.capture());
        assertSame(Retryer.NEVER_RETRY, retryer.getValue());
        verify(builder, never()).addCapability(any());
    }

    @Test
    void circuitCanBeDisabledIndependently() {
        OutboundProperties props = new OutboundProperties();
        props.setCircuitEnabled(false);
        FeignBuilderCustomizer customizer = config.rfFeignOutboundCustomizer(
                props, new OutboundCircuitBreakerRegistry());
        Feign.Builder builder = mock(Feign.Builder.class, RETURNS_SELF);

        customizer.customize(builder);

        verify(builder).retryer(any(RfFeignRetryer.class));
        verify(builder, never()).addCapability(any());
    }
}
