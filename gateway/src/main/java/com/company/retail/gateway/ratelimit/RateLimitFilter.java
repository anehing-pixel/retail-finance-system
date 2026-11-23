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
 * <p><b>Redis 不可用时默认 fail-open 放行</b>：限流层挂掉就把主链路打挂，
 * 是本末倒置；且真正的重复提交已被服务内幂等挡住。生产可配置切换 fail-closed。</p>
 *
 * <p>顺序排在 {@code RequestIdFilter}(-100) 之后，保证被限流的响应也带请求号。</p>
 */
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitProperties props;
    private final RateLimitRuleMatcher matcher;
    private final TokenBucketLimiter limiter;
    private final ObjectMapper mapper;
    /** 客户端 IP 解析（可信代理网段为空时不采信转发头，行为与上线前一致）。 */
    private final ClientIpResolver clientIpResolver;

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
                    if (props.isFailOpen()) {
                        log.warn("[网关限流] Redis 不可用，fail-open 放行 path={} err={}", path, e.toString());
                        return chain.filter(exchange);
                    }
                    log.warn("[网关限流] Redis 不可用，fail-closed 拒绝 path={} err={}", path, e.toString());
                    exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    return exchange.getResponse().setComplete();
                });
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
