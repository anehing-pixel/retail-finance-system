package com.company.retail.gateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流过滤器：放行 / 429 语义 / 开关 / 计数键隔离。
 */
class RateLimitFilterTest {

    private final AtomicLong clock = new AtomicLong(0L);

    private RateLimitFilter filter(int capacity, double refill, String trustedCidrs, boolean enabled) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(enabled);
        properties.setDefaultPolicy(new RateLimitProperties.Policy(capacity, refill));
        properties.setTrustedProxyCidrs(trustedCidrs);
        properties.setRules(List.of(new RateLimitProperties.Rule("/api/auth/", 2, refill)));
        return new RateLimitFilter(
                properties,
                new RateLimitPolicyResolver(properties),
                new ClientIpResolver(trustedCidrs),
                new RateLimitRegistry(100, 60L),
                new ObjectMapper(),
                clock::get);
    }

    private MockServerWebExchange exchange(String path, String remoteIp, String forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path)
                .header(RequestIdFilterStub.HEADER, "trace-0001");
        builder.remoteAddress(new InetSocketAddress(remoteIp, 12345));
        if (forwardedFor != null) {
            builder.header(ClientIpResolver.X_FORWARDED_FOR, forwardedFor);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private boolean invoke(RateLimitFilter filter, ServerWebExchange exchange) {
        AtomicBoolean called = new AtomicBoolean(false);
        GatewayFilterChain chain = new GatewayFilterChain() {
            @Override
            public Mono<Void> filter(ServerWebExchange ex) {
                called.set(true);
                return Mono.empty();
            }
        };
        filter.filter(exchange, chain).block();
        return called.get();
    }

    @Test
    @DisplayName("额度内放行，并回写限流响应头")
    void allowsWithinQuota() {
        RateLimitFilter filter = filter(2, 1d, "", true);
        MockServerWebExchange exchange = exchange("/api/loans/1", "10.0.0.5", null);
        assertTrue(invoke(filter, exchange));
        assertEquals("2", exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit"));
        assertEquals("1", exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("超额度返回 429 + RF_GATEWAY_001 + Retry-After，且不再向下游转发")
    void rejectsOverQuota() {
        RateLimitFilter filter = filter(1, 1d, "", true);
        assertTrue(invoke(filter, exchange("/api/loans/1", "10.0.0.5", null)));
        MockServerWebExchange second = exchange("/api/loans/1", "10.0.0.5", null);
        assertFalse(invoke(filter, second), "被限流的请求不应再转发到下游");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getResponse().getStatusCode());
        assertTrue(second.getResponse().getHeaders().getFirst("Retry-After") != null);
        assertEquals("0", second.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
        String body = second.getResponse().getBodyAsString().block();
        assertTrue(body.contains("RF_GATEWAY_001"), "响应体应为统一响应结构：" + body);
        assertTrue(body.contains("trace-0001"), "响应体应带 traceId：" + body);
    }

    @Test
    @DisplayName("不同客户端 IP 各算各的桶")
    void bucketsAreIsolatedPerClientIp() {
        RateLimitFilter filter = filter(1, 1d, "", true);
        assertTrue(invoke(filter, exchange("/api/loans/1", "10.0.0.5", null)));
        assertFalse(invoke(filter, exchange("/api/loans/1", "10.0.0.5", null)));
        assertTrue(invoke(filter, exchange("/api/loans/1", "10.0.0.6", null)), "另一来源应独立放行");
    }

    @Test
    @DisplayName("可信代理场景下按 X-Forwarded-For 隔离")
    void bucketsAreIsolatedPerForwardedIp() {
        RateLimitFilter filter = filter(1, 1d, "127.0.0.1/32", true);
        assertTrue(invoke(filter, exchange("/api/loans/1", "127.0.0.1", "203.0.113.9")));
        assertFalse(invoke(filter, exchange("/api/loans/1", "127.0.0.1", "203.0.113.9")));
        assertTrue(invoke(filter, exchange("/api/loans/1", "127.0.0.1", "203.0.113.10")));
    }

    @Test
    @DisplayName("不可信来源伪造 X-Forwarded-For 不能重置限流")
    void spoofedForwardedHeaderCannotBypass() {
        RateLimitFilter filter = filter(1, 1d, "127.0.0.1/32", true);
        assertTrue(invoke(filter, exchange("/api/loans/1", "10.0.0.5", "203.0.113.9")));
        assertFalse(invoke(filter, exchange("/api/loans/1", "10.0.0.5", "198.51.100.4")),
                "直连来源不在可信网段，换转发头也不应重置桶");
    }

    @Test
    @DisplayName("路径级规则比默认策略更严")
    void strictRuleApplies() {
        RateLimitFilter filter = filter(10, 10d, "", true);
        assertTrue(invoke(filter, exchange("/api/auth/login", "10.0.0.5", null)));
        assertTrue(invoke(filter, exchange("/api/auth/login", "10.0.0.5", null)));
        assertFalse(invoke(filter, exchange("/api/auth/login", "10.0.0.5", null)));
    }

    @Test
    @DisplayName("总开关关闭后完全放行")
    void disabledPassesEverything() {
        RateLimitFilter filter = filter(1, 1d, "", false);
        for (int i = 0; i < 5; i++) {
            assertTrue(invoke(filter, exchange("/api/loans/1", "10.0.0.5", null)));
        }
    }

    @Test
    @DisplayName("令牌随时间补充后恢复放行")
    void recoversAfterRefill() {
        RateLimitFilter filter = filter(1, 1d, "", true);
        assertTrue(invoke(filter, exchange("/api/loans/1", "10.0.0.5", null)));
        assertFalse(invoke(filter, exchange("/api/loans/1", "10.0.0.5", null)));
        clock.addAndGet(1_000_000_000L);
        assertTrue(invoke(filter, exchange("/api/loans/1", "10.0.0.5", null)));
    }

    /** 仅用于取真实请求头常量，避免测试里硬编码字符串漂移。 */
    static final class RequestIdFilterStub {
        static final String HEADER = "X-Request-Id";
    }
}
