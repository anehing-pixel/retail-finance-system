package com.company.retail.common.outbound;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器注册表（P0-7）：按 channel 隔离状态，避免一个下游故障误伤其他通道。
 */
public final class OutboundCircuitBreakerRegistry {

    private final ConcurrentHashMap<String, OutboundCircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Integer defaultFailureThreshold;
    private final Duration defaultOpenDuration;

    /** 由 {@link #breaker(String, OutboundPolicy)} 按策略创建。 */
    public OutboundCircuitBreakerRegistry() {
        this(null, null);
    }

    /** 固定阈值注册表（单测/简单装配用）。 */
    public OutboundCircuitBreakerRegistry(int failureThreshold, Duration openDuration) {
        this(Integer.valueOf(failureThreshold), openDuration);
    }

    private OutboundCircuitBreakerRegistry(Integer failureThreshold, Duration openDuration) {
        this.defaultFailureThreshold = failureThreshold;
        this.defaultOpenDuration = openDuration;
    }

    public OutboundCircuitBreaker breaker(String channel, OutboundPolicy policy) {
        return breakers.computeIfAbsent(channel, key ->
                new OutboundCircuitBreaker(policy.getFailureThreshold(), policy.getOpenDuration()));
    }

    public OutboundCircuitBreaker breaker(String channel) {
        if (defaultFailureThreshold == null) {
            throw new IllegalStateException("未配置默认熔断阈值，请使用 breaker(channel, policy)");
        }
        return breakers.computeIfAbsent(channel, key ->
                new OutboundCircuitBreaker(defaultFailureThreshold, defaultOpenDuration));
    }
}
