package com.company.retail.gateway.ratelimit;

/**
 * 限流策略：桶容量（突发额度）与补充速率（每秒令牌数）。
 *
 * @param capacity        桶容量，突发请求上限
 * @param refillPerSecond 每秒补充令牌数，决定稳态吞吐
 */
public record RateLimitPolicy(int capacity, double refillPerSecond) {

    public RateLimitPolicy {
        if (capacity <= 0) {
            throw new IllegalArgumentException("限流容量必须为正数：" + capacity);
        }
        if (!(refillPerSecond > 0d)) {
            throw new IllegalArgumentException("补充速率必须为正数：" + refillPerSecond);
        }
    }
}
