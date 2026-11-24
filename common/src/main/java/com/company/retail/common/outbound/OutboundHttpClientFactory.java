package com.company.retail.common.outbound;

import java.net.http.HttpClient;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 出站客户端工厂（P0-7）：按 channel 缓存客户端与连接池，熔断器同 channel 共享。
 *
 * <p>精确装配：由 {@link RfOutboundConfig} 注册为 bean；挡板模式（URL 为空）的旧构造器
 * 可用 {@link #defaults()}，不依赖 Spring 上下文。</p>
 */
public final class OutboundHttpClientFactory {

    private final OutboundProperties properties;
    private final OutboundCircuitBreakerRegistry registry;
    private final ConcurrentHashMap<String, OutboundHttpClient> clients = new ConcurrentHashMap<>();

    public OutboundHttpClientFactory(OutboundProperties properties) {
        this(properties, new OutboundCircuitBreakerRegistry());
    }

    public OutboundHttpClientFactory(OutboundProperties properties, OutboundCircuitBreakerRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    public OutboundHttpClient client(String channel, OutboundProfile profile) {
        return client(channel, properties.policyOf(profile));
    }

    /**
     * 强制不重试的客户端：资金动作 / 写入动作 / 锁内转发使用。
     *
     * <p>即使配置把对应 profile 的 maxAttempts 调大，本方法仍固定为 1 次，
     * 保证「绝不自动重试」是代码级红线，而不是配置约定。</p>
     */
    public OutboundHttpClient noRetryClient(String channel, OutboundProfile profile) {
        OutboundPolicy source = properties.policyOf(profile);
        OutboundPolicy noRetry = OutboundPolicy.of(source.getConnectTimeout(), source.getReadTimeout(),
                1, source.getRetryBackoff(), source.getFailureThreshold(), source.getOpenDuration());
        return client(channel, noRetry);
    }

    public OutboundHttpClient client(String channel, OutboundPolicy policy) {
        return clients.computeIfAbsent(channel, key -> {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(policy.getConnectTimeout())
                    .build();
            return new OutboundHttpClient(key, policy, registry.breaker(key, policy),
                    properties.isEnabled(), properties.isCircuitEnabled(), http, null);
        });
    }

    /** 默认策略实例（无 Spring 上下文的挡板/单测用）。 */
    public static OutboundHttpClientFactory defaults() {
        return new OutboundHttpClientFactory(new OutboundProperties());
    }
}
