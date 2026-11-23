package com.company.retail.gateway.ratelimit;

import com.company.platform.response.ApiResponse;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.gateway.RequestIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 网关令牌桶限流过滤器。
 *
 * <p><b>位置</b>：在 {@code RequestIdFilter}（order = -100）之后（order = -90），
 * 因此 429 响应体里带得上 traceId；且在路由转发之前命中——被限流的请求<b>不会</b>
 * 打到下游服务，不占用数据库连接。</p>
 *
 * <p><b>计数键</b>：命中段标识 + 客户端 IP（见 {@link ClientIpResolver} 的可信代理口径）。
 * 同一路由段的不同客户端各算各的，同一客户端在不同段互不挤占。</p>
 *
 * <p><b>不写个人信息</b>：429 响应体与日志都不含客户端 IP（IP 属可识别个人信息的标识符，
 * 对齐 {@code R08 §5}）；需要定位来源看边缘 Nginx 的访问日志即可。</p>
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);

    static final String HEADER_LIMIT = "X-RateLimit-Limit";
    static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    static final String HEADER_RESET = "X-RateLimit-Reset";
    static final String HEADER_RETRY_AFTER = "Retry-After";

    private final RateLimitProperties properties;
    private final RateLimitPolicyResolver policyResolver;
    private final ClientIpResolver clientIpResolver;
    private final RateLimitRegistry registry;
    private final ObjectMapper objectMapper;
    private final LongSupplier nanoClock;

    @Autowired
    public RateLimitFilter(RateLimitProperties properties,
                           RateLimitPolicyResolver policyResolver,
                           ClientIpResolver clientIpResolver,
                           RateLimitRegistry registry,
                           ObjectMapper objectMapper) {
        this(properties, policyResolver, clientIpResolver, registry, objectMapper, System::nanoTime);
    }

    public RateLimitFilter(RateLimitProperties properties,
                           RateLimitPolicyResolver policyResolver,
                           ClientIpResolver clientIpResolver,
                           RateLimitRegistry registry,
                           ObjectMapper objectMapper,
                           LongSupplier nanoClock) {
        this.properties = properties;
        this.policyResolver = policyResolver;
        this.clientIpResolver = clientIpResolver;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.nanoClock = nanoClock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        RateLimitPolicyResolver.Resolved resolved = policyResolver.resolve(path);
        String clientIp = clientIpResolver.resolve(exchange.getRequest());
        String key = resolved.segment() + "|" + clientIp;

        RateLimitDecision decision = registry.acquire(key, resolved.policy(), nanoClock.getAsLong());

        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set(HEADER_LIMIT, Integer.toString(decision.limit()));
        headers.set(HEADER_REMAINING, Integer.toString(decision.remaining()));
        if (decision.allowed()) {
            return chain.filter(exchange);
        }

        headers.set(HEADER_RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        headers.set(HEADER_RESET, Long.toString(Instant.now().getEpochSecond() + decision.retryAfterSeconds()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);

        String traceId = exchange.getRequest().getHeaders().getFirst(RequestIdFilter.HEADER);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("limit", decision.limit());
        details.put("remaining", decision.remaining());
        details.put("retryAfterSeconds", decision.retryAfterSeconds());
        details.put("policy", resolved.segment());
        ApiResponse<Object> body = ApiResponse.fail(
                RFErrorCode.RF_GATEWAY_001.getCode(),
                RFErrorCode.RF_GATEWAY_001.getMessage(),
                traceId,
                details);

        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            payload = ("{\"code\":\"" + RFErrorCode.RF_GATEWAY_001.getCode() + "\",\"message\":\""
                    + RFErrorCode.RF_GATEWAY_001.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        LOG.warn("网关限流命中 segment={} limit={} remaining={} retryAfterSeconds={}",
                resolved.segment(), decision.limit(), decision.remaining(), decision.retryAfterSeconds());

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(payload);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
