package com.company.retail.common.outbound;

import feign.Request;
import feign.RetryableException;
import feign.Retryer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P0-7：Feign 只对传输层失败重试，HTTP 状态错误不进入 Retryer。
 */
class RfFeignRetryerTest {

    private static RetryableException transportFailure() {
        Request request = Request.create(Request.HttpMethod.GET, "/test", Map.of(), null, StandardCharsets.UTF_8);
        return new RetryableException(-1, "connect timed out", Request.HttpMethod.GET,
                (Throwable) null, (Long) null, request);
    }

    @Test
    void retriesOnceThenPropagates() {
        Retryer retryer = new RfFeignRetryer(2, Duration.ZERO);

        assertDoesNotThrow(() -> retryer.continueOrPropagate(transportFailure()));
        assertThrows(RetryableException.class, () -> retryer.continueOrPropagate(transportFailure()));
    }

    @Test
    void neverRetriesWhenMaxAttemptsIsOne() {
        Retryer retryer = new RfFeignRetryer(1, Duration.ZERO);

        assertThrows(RetryableException.class, () -> retryer.continueOrPropagate(transportFailure()));
    }

    @Test
    void cloneStartsWithFreshAttemptBudget() throws CloneNotSupportedException {
        RfFeignRetryer retryer = new RfFeignRetryer(2, Duration.ZERO);
        retryer.continueOrPropagate(transportFailure());

        Retryer clone = retryer.clone();

        assertDoesNotThrow(() -> clone.continueOrPropagate(transportFailure()));
    }
}
