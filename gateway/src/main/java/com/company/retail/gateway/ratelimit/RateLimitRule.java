package com.company.retail.gateway.ratelimit;

/**
 * 一条路径级限流规则：命中该前缀的请求使用本规则的策略。
 *
 * @param pathPrefix      路径前缀（网关侧路径，如 {@code /api/auth/}）
 * @param capacity        桶容量
 * @param refillPerSecond 每秒补充令牌数
 */
public record RateLimitRule(String pathPrefix, int capacity, double refillPerSecond) {

    public RateLimitRule {
        if (pathPrefix == null || pathPrefix.isBlank()) {
            throw new IllegalArgumentException("限流路径前缀不得为空");
        }
    }

    public RateLimitPolicy policy() {
        return new RateLimitPolicy(capacity, refillPerSecond);
    }
}
