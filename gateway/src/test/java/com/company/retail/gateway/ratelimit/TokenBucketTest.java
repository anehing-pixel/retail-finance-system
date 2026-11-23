package com.company.retail.gateway.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌桶行为：突发额度、匀速补充、容量钳制。
 */
class TokenBucketTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    @DisplayName("容量内的突发请求全部放行，超出即拒绝")
    void burstWithinCapacity() {
        TokenBucket bucket = new TokenBucket(3, 1d, 0L);
        assertTrue(bucket.tryAcquire(0L).allowed());
        assertTrue(bucket.tryAcquire(0L).allowed());
        assertTrue(bucket.tryAcquire(0L).allowed());
        RateLimitDecision denied = bucket.tryAcquire(0L);
        assertFalse(denied.allowed());
        assertEquals(3, denied.limit());
        assertEquals(0, denied.remaining());
    }

    @Test
    @DisplayName("拒绝时给出至少 1 秒的退避建议")
    void retryAfterAtLeastOneSecond() {
        TokenBucket bucket = new TokenBucket(1, 0.5d, 0L);
        assertTrue(bucket.tryAcquire(0L).allowed());
        RateLimitDecision denied = bucket.tryAcquire(0L);
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterSeconds() >= 1L, "退避建议应 ≥ 1 秒");
    }

    @Test
    @DisplayName("时间推进后补充令牌，恢复放行")
    void refillAfterElapsed() {
        TokenBucket bucket = new TokenBucket(1, 1d, 0L);
        assertTrue(bucket.tryAcquire(0L).allowed());
        assertFalse(bucket.tryAcquire(0L).allowed());
        // 推进 1 秒 → 补 1 个令牌
        assertTrue(bucket.tryAcquire(SECOND).allowed());
        assertFalse(bucket.tryAcquire(SECOND).allowed());
    }

    @Test
    @DisplayName("补充不超过容量上限（长时间空闲也不超发）")
    void refillClampedToCapacity() {
        TokenBucket bucket = new TokenBucket(2, 10d, 0L);
        bucket.tryAcquire(0L);
        bucket.tryAcquire(0L);
        assertFalse(bucket.tryAcquire(0L).allowed());
        // 空闲 100 秒，理论可补 1000 个，但容量上限为 2
        assertEquals(2d, bucket.availableTokens(100 * SECOND), 1e-9);
        assertTrue(bucket.tryAcquire(100 * SECOND).allowed());
        assertTrue(bucket.tryAcquire(100 * SECOND).allowed());
        assertFalse(bucket.tryAcquire(100 * SECOND).allowed());
    }

    @Test
    @DisplayName("非法参数直接拒绝（配置错误 fail-fast）")
    void invalidParametersRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 1d, 0L));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1, 0d, 0L));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1, -1d, 0L));
    }

    @Test
    @DisplayName("时钟回拨不补发令牌")
    void clockGoingBackwardsDoesNotRefill() {
        TokenBucket bucket = new TokenBucket(1, 100d, 10 * SECOND);
        assertTrue(bucket.tryAcquire(10 * SECOND).allowed());
        assertFalse(bucket.tryAcquire(5 * SECOND).allowed());
    }
}
