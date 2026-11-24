package com.company.retail.common.outbound;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 进程内三态熔断器（P0-7）：CLOSED → OPEN → HALF_OPEN。
 *
 * <p>线程安全（{@code synchronized}）；按 channel 由
 * {@link OutboundCircuitBreakerRegistry} 隔离。打开期间直接拒绝调用，
 * 由调用方走既有 fail-closed 分支（不改变错误码）。</p>
 */
public final class OutboundCircuitBreaker {

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final int failureThreshold;
    private final long openNanos;
    private final LongSupplier nanoClock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openUntilNanos;
    private boolean probeInFlight;

    public OutboundCircuitBreaker(int failureThreshold, Duration openDuration) {
        this(failureThreshold, openDuration, System::nanoTime);
    }

    public OutboundCircuitBreaker(int failureThreshold, Duration openDuration, LongSupplier nanoClock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold 必须 >= 1");
        }
        if (openDuration == null || openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("openDuration 必须 > 0");
        }
        this.failureThreshold = failureThreshold;
        this.openNanos = openDuration.toNanos();
        this.nanoClock = nanoClock;
    }

    /** 是否放行本次调用；打开状态返回 false，半开只放一个探针。 */
    public synchronized boolean tryAcquire() {
        long now = nanoClock.getAsLong();
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN) {
            if (now < openUntilNanos) {
                return false;
            }
            state = State.HALF_OPEN;
            probeInFlight = true;
            return true;
        }
        if (!probeInFlight) {
            probeInFlight = true;
            return true;
        }
        return false;
    }

    public synchronized void onSuccess() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        probeInFlight = false;
    }

    public synchronized void onFailure() {
        if (state == State.HALF_OPEN) {
            open(nanoClock.getAsLong());
            return;
        }
        if (++consecutiveFailures >= failureThreshold) {
            open(nanoClock.getAsLong());
        }
    }

    public synchronized State state() {
        return state;
    }

    private void open(long now) {
        state = State.OPEN;
        openUntilNanos = now + openNanos;
        probeInFlight = false;
        consecutiveFailures = failureThreshold;
    }
}
