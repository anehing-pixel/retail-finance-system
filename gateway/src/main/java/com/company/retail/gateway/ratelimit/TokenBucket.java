package com.company.retail.gateway.ratelimit;

/**
 * 令牌桶（token bucket）——网关限流的最小机制件。
 *
 * <p>语义：桶内令牌上限 = 突发额度（{@code capacity}），按 {@code refillPerSecond} 匀速补充。
 * 取到令牌即放行；桶空则拒绝，并给出「还需等待多少秒」，客户端据此退避即可恢复，
 * 不会演变成重试风暴。</p>
 *
 * <p>零 Spring 依赖（对齐 {@code AGENTS.md §2}：机制件不引框架注解）；时钟由调用方以
 * {@code nowNanos} 传入，单测可完全确定性地验证补充与耗尽行为。</p>
 */
public final class TokenBucket {

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private final int capacity;
    private final double refillPerSecond;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(int capacity, double refillPerSecond, long nowNanos) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("令牌桶容量必须为正数：" + capacity);
        }
        if (!(refillPerSecond > 0d)) {
            throw new IllegalArgumentException("令牌补充速率必须为正数：" + refillPerSecond);
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokens = capacity;
        this.lastRefillNanos = nowNanos;
    }

    /**
     * 取一个令牌：取到放行，桶空拒绝。
     *
     * @param nowNanos 单调时钟纳秒值（由调用方注入，便于确定性测试）
     */
    public synchronized RateLimitDecision tryAcquire(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1d) {
            tokens -= 1d;
            return RateLimitDecision.allowed(capacity, (int) Math.floor(tokens));
        }
        double missing = 1d - tokens;
        long retryAfterSeconds = (long) Math.ceil(missing / refillPerSecond);
        return RateLimitDecision.rejected(capacity, 0, retryAfterSeconds);
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed <= 0) {
            // 时钟回拨（防御性）：不补充，也不回退时间戳，避免一次性补出过多令牌
            return;
        }
        double replenished = tokens + (elapsed / NANOS_PER_SECOND) * refillPerSecond;
        tokens = Math.min(capacity, replenished);
        lastRefillNanos = nowNanos;
    }

    public int capacity() {
        return capacity;
    }

    public double refillPerSecond() {
        return refillPerSecond;
    }

    /** 当前桶内令牌数（诊断用，调用前会先按给定时间补充）。 */
    public synchronized double availableTokens(long nowNanos) {
        refill(nowNanos);
        return tokens;
    }
}
