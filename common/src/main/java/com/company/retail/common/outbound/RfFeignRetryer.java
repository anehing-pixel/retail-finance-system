package com.company.retail.common.outbound;

import feign.RetryableException;
import feign.Retryer;

import java.time.Duration;

/**
 * Feign 重试器（P0-7）：只对传输层失败（{@link RetryableException}，通常是
 * 连接失败/读超时）重试；HTTP 4xx/5xx 由默认 ErrorDecoder 抛 {@code FeignException}，
 * 不会进入本类——门禁与代付链路不因 5xx 自动重试而拉长。</p>
 */
public final class RfFeignRetryer implements Retryer {

    private final int maxAttempts;
    private final Duration backoff;
    private int attempt = 1;

    public RfFeignRetryer(int maxAttempts, Duration backoff) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoff = backoff == null ? Duration.ZERO : backoff;
    }

    @Override
    public void continueOrPropagate(RetryableException exception) {
        if (attempt++ >= maxAttempts) {
            throw exception;
        }
        if (!backoff.isZero()) {
            try {
                Thread.sleep(backoff.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw exception;
            }
        }
    }

    @Override
    public Retryer clone() {
        return new RfFeignRetryer(maxAttempts, backoff);
    }
}
