package com.company.retail.gateway.ratelimit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.company.retail.gateway.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关限流过滤器（master 实现）行为单测。
 *
 * <p>覆盖：放行 / 429 拒绝语义（响应体含 {@code RF_GATEWAY_001} + {@code Retry-After}）/
 * Redis 不可用时 fail-open 与 fail-closed 两种取舍 / 计数键按调用方隔离 /
 * 可信代理网段生效时采信 {@code X-Forwarded-For}。</p>
 */
class RateLimitFilterTest {

    /** 记录 Lua 脚本入参（桶键）的假 Redis。 */
    static final class FakeRedis {
        final Long result;
        final RuntimeException failure;
        final List<Object[]> calls = new ArrayList<>();
        final ReactiveStringRedisTemplate template;

        FakeRedis(Long result, RuntimeException failure) {
            this.result = result;
            this.failure = failure;
            this.template = Mockito.mock(ReactiveStringRedisTemplate.class, invocation -> {
                if ("execute".equals(invocation.getMethod().getName())) {
                    calls.add(invocation.getArguments());
                    if (failure != null) {
                        return Flux.error(failure);
                    }
                    return Flux.just(result);
                }
                return Mockito.RETURNS_DEFAULTS.answer(invocation);
            });
        }
    }

    private RateLimitFilter filter(RateLimitProperties props, FakeRedis redis) {
        return new RateLimitFilter(props,
                new RateLimitRuleMatcher(props.getRules()),
                new TokenBucketLimiter(redis.template),
                new ObjectMapper());
    }

    private RateLimitProperties props(boolean failOpen, String trustedCidrs) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setFailOpen(failOpen);
        properties.setKeyBy("auth_or_ip");
        properties.setKeyPrefix("rf:rl:");
        properties.setTrustedProxyCidrs(trustedCidrs);
        RateLimitProperties.Rule rule = new RateLimitProperties.Rule();
        rule.setPath("/api/**");
        rule.setReplenishRate(1);
        rule.setBurstCapacity(2);
        properties.setRules(List.of(rule));
        return properties;
    }

    private MockServerWebExchange exchange(String path, String remoteIp, String authHeader, String forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path)
                .header(RequestIdFilter.HEADER, "trace-0001");
        builder.remoteAddress(new InetSocketAddress(remoteIp, 12345));
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
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
    @DisplayName("额度内放行，不返回 429")
    void allowsWithinQuota() {
        RateLimitFilter filter = filter(props(true, ""), new FakeRedis(1L, null));
        MockServerWebExchange exchange = exchange("/api/loans/1", "10.0.0.5", null, null);

        assertTrue(invoke(filter, exchange));
        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("超额度返回 429 + RF_GATEWAY_001 + Retry-After，且不再向下游转发")
    void rejectsOverQuota() {
        RateLimitFilter filter = filter(props(true, ""), new FakeRedis(0L, null));
        MockServerWebExchange exchange = exchange("/api/loans/1", "10.0.0.5", null, null);

        assertFalse(invoke(filter, exchange));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        assertEquals("1", exchange.getResponse().getHeaders().getFirst("Retry-After"));
        String body = exchange.getResponse().getBodyAsString().block();
        assertNotNull(body);
        assertTrue(body.contains("RF_GATEWAY_001"), "响应体必须含错误码：" + body);
    }

    @Test
    @DisplayName("Redis 不可用且 fail-open 时放行")
    void failOpenPassesThrough() {
        RateLimitFilter filter = filter(props(true, ""), new FakeRedis(null, new RuntimeException("redis down")));
        MockServerWebExchange exchange = exchange("/api/loans/1", "10.0.0.5", null, null);

        assertTrue(invoke(filter, exchange));
    }

    @Test
    @DisplayName("Redis 不可用且 fail-closed 时返回 503")
    void failClosedRejects() {
        RateLimitFilter filter = filter(props(false, ""), new FakeRedis(null, new RuntimeException("redis down")));
        MockServerWebExchange exchange = exchange("/api/loans/1", "10.0.0.5", null, null);

        assertFalse(invoke(filter, exchange));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Redis 不可用且 fail-open：输出 ALERT 告警标记，且不泄露凭据")
    void failOpenEmitsAlertMarker() {
        Logger logger = (Logger) LoggerFactory.getLogger(RateLimitFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RateLimitFilter filter = filter(props(true, ""),
                    new FakeRedis(null, new RuntimeException("redis down")));
            MockServerWebExchange exchange = exchange("/api/loans/1", "10.0.0.5",
                    "Bearer super-secret-token", null);

            assertTrue(invoke(filter, exchange));

            ILoggingEvent event = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("ALERT_RATE_LIMIT_REDIS_UNAVAILABLE"))
                    .findFirst().orElseThrow();
            assertEquals(Level.ERROR, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("failMode=OPEN"));
            assertFalse(event.getFormattedMessage().contains("super-secret-token"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("Redis 不可用且 fail-closed：输出 ALERT 告警标记")
    void failClosedEmitsAlertMarker() {
        Logger logger = (Logger) LoggerFactory.getLogger(RateLimitFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RateLimitFilter filter = filter(props(false, ""),
                    new FakeRedis(null, new RuntimeException("redis down")));
            MockServerWebExchange exchange = exchange("/api/loans/1", "10.0.0.5", null, null);

            assertFalse(invoke(filter, exchange));

            ILoggingEvent event = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("ALERT_RATE_LIMIT_REDIS_UNAVAILABLE"))
                    .findFirst().orElseThrow();
            assertEquals(Level.ERROR, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("failMode=CLOSED"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("同一实例 10s 内重复失败只输出一次 ALERT（防日志洪泛）")
    void alertIsThrottled() {
        Logger logger = (Logger) LoggerFactory.getLogger(RateLimitFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RateLimitFilter filter = filter(props(false, ""),
                    new FakeRedis(null, new RuntimeException("redis down")));
            invoke(filter, exchange("/api/loans/1", "10.0.0.5", null, null));
            invoke(filter, exchange("/api/loans/2", "10.0.0.6", null, null));

            long alerts = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("ALERT_RATE_LIMIT_REDIS_UNAVAILABLE"))
                    .count();
            assertEquals(1, alerts, "同一实例 10s 内只应输出一条 ALERT");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("计数键按调用方隔离：不同 Authorization 头落到不同桶")
    void keyIsolatesCallers() {
        FakeRedis redis = new FakeRedis(1L, null);
        RateLimitFilter filter = filter(props(true, ""), redis);

        invoke(filter, exchange("/api/loans/1", "10.0.0.5", "Bearer token-a", null));
        invoke(filter, exchange("/api/loans/2", "10.0.0.5", "Bearer token-b", null));

        assertEquals(2, redis.calls.size());
        Object keyA = redis.calls.get(0)[1];
        Object keyB = redis.calls.get(1)[1];
        assertNotEquals(keyA, keyB, "不同调用方不得共用同一个桶");
    }

    @Test
    @DisplayName("未配置可信代理时忽略 X-Forwarded-For；配置后采信真实客户端 IP")
    void trustedProxyControlsForwardedHeader() {
        FakeRedis plainRedis = new FakeRedis(1L, null);
        RateLimitFilter plain = filter(props(true, ""), plainRedis);
        invoke(plain, exchange("/api/loans/1", "10.0.0.5", null, "1.2.3.4"));
        String plainKey = String.valueOf(plainRedis.calls.get(0)[1]);
        assertTrue(plainKey.contains("10.0.0.5"), "未配置可信代理时应取直连地址：" + plainKey);
        assertFalse(plainKey.contains("1.2.3.4"), "未配置可信代理时不得采信转发头：" + plainKey);

        FakeRedis proxyRedis = new FakeRedis(1L, null);
        RateLimitFilter withProxy = filter(props(true, "10.0.0.0/8"), proxyRedis);
        invoke(withProxy, exchange("/api/loans/1", "10.0.0.5", null, "1.2.3.4"));
        String proxyKey = String.valueOf(proxyRedis.calls.get(0)[1]);
        assertTrue(proxyKey.contains("1.2.3.4"), "可信代理时应采信转发链中的真实客户端：" + proxyKey);
    }
}
