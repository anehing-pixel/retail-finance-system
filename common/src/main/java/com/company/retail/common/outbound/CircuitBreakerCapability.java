package com.company.retail.common.outbound;

import feign.Capability;
import feign.Client;
import feign.Request;
import feign.Response;

import java.io.IOException;

/**
 * Feign 客户端级熔断（P0-7）：包装 {@link Client}，按门禁路径选择独立 breaker。
 *
 * <p>熔断打开时抛 {@link CircuitOpenException}，不再发起网络调用；
 * 调用方按既有 fail-closed 分支处理。</p>
 */
public final class CircuitBreakerCapability implements Capability {

    private final OutboundCircuitBreakerRegistry registry;
    private final OutboundPolicy policy;

    public CircuitBreakerCapability(OutboundCircuitBreakerRegistry registry, OutboundPolicy policy) {
        this.registry = registry;
        this.policy = policy;
    }

    @Override
    public Client enrich(Client client) {
        return (request, options) -> {
            String channel = channelOf(request);
            OutboundCircuitBreaker breaker = registry.breaker(channel, policy);
            if (!breaker.tryAcquire()) {
                throw new CircuitOpenException(channel);
            }
            try {
                Response response = client.execute(request, options);
                if (response.status() == 429 || response.status() >= 500) {
                    breaker.onFailure();
                } else {
                    breaker.onSuccess();
                }
                return response;
            } catch (IOException | RuntimeException e) {
                breaker.onFailure();
                throw e;
            }
        };
    }

    /** 三个门禁各自独立熔断，避免一个下游故障误伤其他门禁。 */
    static String channelOf(Request request) {
        String url = request.url();
        if (url.contains("/api/contracts/")) {
            return "feign-contract-gate";
        }
        if (url.contains("/api/consents/")) {
            return "feign-consent-gate";
        }
        if (url.contains("/api/underwriting/")) {
            return "feign-underwriting-gate";
        }
        return "feign-gate";
    }
}
