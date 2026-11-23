package com.company.retail.gateway.ratelimit;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 令牌桶限流器（Redis + Lua 原子扣减）。
 *
 * <p><b>为什么是 Redis 而不是本地计数</b>：网关多实例部署时，本地计数各算各的，
 * 实际总放行量是「单实例额度 × 实例数」，限流失效。Redis 是唯一共享点。</p>
 *
 * <p><b>为什么用 Lua</b>：读取令牌 → 计算 → 写回必须原子，
 * 否则并发请求会读到同一个令牌数并都放行。</p>
 */
@Component
public class TokenBucketLimiter {

    private static final String LUA_PATH = "lua/token_bucket.lua";

    private final ReactiveStringRedisTemplate redis;
    private final RedisScript<Long> script;

    public TokenBucketLimiter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
        DefaultRedisScript<Long> lua = new DefaultRedisScript<>();
        lua.setLocation(new ClassPathResource(LUA_PATH));
        lua.setResultType(Long.class);
        this.script = lua;
    }

    /**
     * 取令牌。
     *
     * @param key           桶键
     * @param capacity      桶容量
     * @param replenishRate 补充速率（令牌 / 秒）
     * @return true = 放行；false = 限流
     */
    public Mono<Boolean> tryAcquire(String key, int capacity, int replenishRate) {
        long now = System.currentTimeMillis();
        // execute 返回 Flux（脚本结果序列）；next() 收敛为 Mono 再做空值兜底
        return redis.execute(script, List.of(key),
                        List.of(String.valueOf(capacity), String.valueOf(replenishRate),
                                String.valueOf(now), "1"))
                .next()
                .map(result -> result != null && result == 1L)
                .defaultIfEmpty(false);
    }
}
