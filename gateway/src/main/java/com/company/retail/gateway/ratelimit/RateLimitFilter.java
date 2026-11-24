package com.company.retail.gateway.ratelimit;

import com.company.retail.domain.exception.RFErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网关限流过滤器（Redis 令牌桶）。
 *
 * <p><b>只在 {@code retail.gateway.rate-limit.mode=redis}（缺省）时装配</b>；
 * 切到 {@code memory} 时改由进程内令牌桶实现接管（{@link InMemoryRateLimitFilter}），
 * 两者<b>互斥</b>，不会出现双重限流。开关口径见 {@code R02 §3.3}。</p>
 *
 * <p><b>定位</b>：网关是统一入口，在路由切流之前做令牌桶限流，
 * 防止外部对同一入口高频刷请求（外部回调、爬取、误重试）。
 * 它是<b>保护层</b>，不是安全边界——真正的正确性防线在各服务内
 * （幂等唯一索引、乐观锁、fail-closed 门禁）。</p>
 *
 * <p><b>Redis 不可用时默认 fail-closed</b>（R11 P0-5）：返回 503，不允许静默绕过限流；
 * 本地开发由 {@code scripts/dev-secrets.sh} 显式置 fail-open。两种模式都输出
 * {@code ALERT_RATE_LIMIT_REDIS_UNAVAILABLE} 告警标记，供日志告警规则接入。</p>
 *
 * <p>顺序排在 {@code RequestIdFilter}(-100) 之后，保证被限流的响应也带请求号。</p>
 */
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** 告警日志节流窗口：Redis 故障时每请求一条 ERROR 会洪泛日志，按实例 10s 一条。 */
    private static final long ALERT_THROTTLE_MS = 10_000L;

    private final RateLimitProperties props;
    private final RateLimitRuleMatcher matcher;
    private final TokenBucketLimiter limiter;
    private final ObjectMapper mapper;
    /** 客户端 IP 解析（可信代理网段为空时不采信转发头，行为与上线前一致）。 */
    private final ClientIpResolver clientIpResolver;

    /** 上次输出 ALERT 的时间（实例级节流，10s 窗口）。 */
    private final AtomicLong lastAlertAtMs = new AtomicLong(0L);

    public RateLimitFilter(RateLimitProperties props, RateLimitRuleMatcher matcher,
                            TokenBucketLimiter limiter, ObjectMapper mapper) {
        this.props = props;
        this.matcher = matcher;
        this.limiter = limiter;
        this.mapper = mapper;
        this.clientIpResolver = new ClientIpResolver(props.getTrustedProxyCidrs());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!props.isEnabled()) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        RateLimitProperties.Rule rule = matcher.match(path);
        if (rule == null) {
            return chain.filter(exchange);
        }
        String clientKey = RateLimitKeyResolver.resolve(
                exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION),
                clientIpResolver.resolve(exchange.getRequest()), props.getKeyBy());
        String key = props.getKeyPrefix() + rule.getPath() + ":" + clientKey;

        return limiter.tryAcquire(key, rule.getBurstCapacity(), rule.getReplenishRate())
                .flatMap(allowed -> allowed ? chain.filter(exchange) : reject(exchange, path))
                .onErrorResume(e -> {
                    logAlert(path, e);
                    if (props.isFailOpen()) {
                        return chain.filter(exchange);
                    }
                    exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    return exchange.getResponse().setComplete();
                });
    }

    /**
     * 稳定告警标记（实例级 10s 节流）：日志平台按 {@code ALERT_RATE_LIMIT_REDIS_UNAVAILABLE} 建规则；
     * 只记路径与异常类型，不记 Authorization / 客户端 IP（B-1 / R08 §5）。
     */
    private void logAlert(String path, Throwable e) {
        long now = System.currentTimeMillis();
        long last = lastAlertAtMs.get();
        if (now - last < ALERT_THROTTLE_MS) {
            return;
        }
        if (lastAlertAtMs.compareAndSet(last, now)) {
            log.error("[ALERT_RATE_LIMIT_REDIS_UNAVAILABLE] mode=redis failMode={} path={} err={}",
                    props.isFailOpen() ? "OPEN" : "CLOSED", path, e.toString());
        }
    }

    /** 被限流：429 + 与平台统一结构一致的响应体（code / message / data / traceId / details）。 */
    private Mono<Void> reject(ServerWebExchange exchange, String path) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        String traceId = exchange.getRequest().getHeaders()
                .getFirst(com.company.retail.gateway.RequestIdFilter.HEADER);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", RFErrorCode.RF_GATEWAY_001.getCode());
        payload.put("message", RFErrorCode.RF_GATEWAY_001.getMessage());
        payload.put("data", null);
        payload.put("traceId", traceId == null ? "" : traceId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("path", path);
        payload.put("details", details);
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            body = ("{\"code\":\"RF_GATEWAY_001\",\"message\":\"请求过于频繁，请稍后重试\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
