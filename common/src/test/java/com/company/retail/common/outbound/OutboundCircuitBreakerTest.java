package com.company.retail.common.outbound;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-7：出站熔断器状态机（CLOSED → OPEN → HALF_OPEN）。
 *
 * <p>假时钟驱动，不 sleep、不启 Spring。</p>
 */
class OutboundCircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(0);

    private OutboundCircuitBreaker breaker(int threshold, Duration openDuration) {
        return new OutboundCircuitBreaker(threshold, openDuration, now::get);
    }

    @Test
    void opensAfterConsecutiveFailures() {
        OutboundCircuitBreaker breaker = breaker(3, Duration.ofSeconds(30));

        breaker.onFailure();
        breaker.onFailure();
        assertTrue(breaker.tryAcquire(), "未达阈值仍应放行");

        breaker.onFailure();
        assertEquals(OutboundCircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquire(), "打开后不应放行");
    }

    @Test
    void successResetsFailureCount() {
        OutboundCircuitBreaker breaker = breaker(3, Duration.ofSeconds(30));

        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onFailure();
        breaker.onFailure();
        assertTrue(breaker.tryAcquire(), "成功应清零连续失败计数");
    }

    @Test
    void halfOpenAllowsExactlyOneProbeAfterCooldown() {
        OutboundCircuitBreaker breaker = breaker(1, Duration.ofSeconds(30));
        breaker.onFailure();
        assertFalse(breaker.tryAcquire());

        now.addAndGet(Duration.ofSeconds(30).toNanos());
        assertTrue(breaker.tryAcquire(), "冷却结束后应放行一个探针");
        assertFalse(breaker.tryAcquire(), "探针在途时不应再放行");
        assertEquals(OutboundCircuitBreaker.State.HALF_OPEN, breaker.state());
    }

    @Test
    void halfOpenSuccessClosesBreaker() {
        OutboundCircuitBreaker breaker = breaker(1, Duration.ofSeconds(30));
        breaker.onFailure();
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        assertTrue(breaker.tryAcquire());

        breaker.onSuccess();

        assertEquals(OutboundCircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.tryAcquire());
    }

    @Test
    void halfOpenFailureReopensForAnotherCooldown() {
        OutboundCircuitBreaker breaker = breaker(1, Duration.ofSeconds(30));
        breaker.onFailure();
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        assertTrue(breaker.tryAcquire());

        breaker.onFailure();
        assertEquals(OutboundCircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquire(), "半开探针失败后应重新打开");
    }

    @Test
    void independentBreakersDoNotShareState() {
        OutboundCircuitBreakerRegistry registry = new OutboundCircuitBreakerRegistry(1, Duration.ofSeconds(30));
        OutboundCircuitBreaker a = registry.breaker("a");
        OutboundCircuitBreaker b = registry.breaker("b");

        a.onFailure();

        assertFalse(a.tryAcquire());
        assertTrue(b.tryAcquire(), "不同 channel 的熔断状态必须隔离");
    }
}
