package com.company.retail.gateway.ratelimit;

/**
 * 一次限流判定的结果。
 *
 * <p>{@code limit} = 该限流策略的桶容量（突发额度）；{@code remaining} = 判定时刻桶内余量；
 * {@code retryAfterSeconds} = 放行下一次请求至少需要等待的秒数（拒绝时至少 1 秒）。</p>
 */
public record RateLimitDecision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {

    public static RateLimitDecision allowed(int limit, int remaining) {
        return new RateLimitDecision(true, limit, Math.max(0, remaining), 0L);
    }

    public static RateLimitDecision rejected(int limit, int remaining, long retryAfterSeconds) {
        return new RateLimitDecision(false, limit, Math.max(0, remaining), Math.max(1L, retryAfterSeconds));
    }
}
