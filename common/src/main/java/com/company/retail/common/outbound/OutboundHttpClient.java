package com.company.retail.common.outbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 出站 HTTP 统一执行器（P0-7）：超时、重试上限、熔断、结构化日志。
 *
 * <p>只封装 JDK {@link HttpClient}，不引入新依赖。重试仅针对传输失败与 429/5xx；
 * 4xx 是确定性拒绝，不重试。每次逻辑调用只向熔断器记一次结果。</p>
 *
 * <p>日志只记 channel / status / attempts / duration，不记请求体（B-1 / R08 §5）。</p>
 */
public final class OutboundHttpClient {

    private static final Logger log = LoggerFactory.getLogger(OutboundHttpClient.class);

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final String channel;
    private final OutboundPolicy policy;
    private final OutboundCircuitBreaker breaker;
    private final boolean enabled;
    private final boolean circuitEnabled;
    private final HttpClient http;
    private final Sleeper sleeper;

    public OutboundHttpClient(String channel, OutboundPolicy policy, OutboundCircuitBreaker breaker,
                              boolean enabled, boolean circuitEnabled, HttpClient http, Sleeper sleeper) {
        this.channel = channel;
        this.policy = policy;
        this.breaker = breaker;
        this.enabled = enabled;
        this.circuitEnabled = circuitEnabled;
        this.http = http;
        this.sleeper = sleeper == null ? duration -> Thread.sleep(duration.toMillis()) : sleeper;
    }

    public OutboundResult get(String url, Map<String, String> headers) {
        return send("GET", url, null, headers);
    }

    public OutboundResult postJson(String url, String body, Map<String, String> headers) {
        return send("POST", url, body, headers);
    }

    private OutboundResult send(String method, String url, String body, Map<String, String> headers) {
        long started = System.nanoTime();
        if (enabled && circuitEnabled && breaker != null && !breaker.tryAcquire()) {
            log.warn("出站熔断打开 channel={} method={} url={}", channel, method, safeUrl(url));
            return OutboundResult.circuitOpen(elapsedMs(started));
        }

        int maxAttempts = enabled ? Math.max(1, policy.getMaxAttempts()) : 1;
        OutboundResult lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = buildRequest(method, url, body, headers);
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (retryableStatus(status) && attempt < maxAttempts) {
                    log.warn("出站重试 channel={} status={} attempt={}/{}", channel, status, attempt, maxAttempts);
                    if (!backoff()) {
                        return interrupted(started, attempt);
                    }
                    continue;
                }
                recordOutcome(status);
                OutboundResult result = OutboundResult.of(status, response.body(), attempt, elapsedMs(started));
                log.info("出站调用 channel={} method={} status={} attempts={} durationMs={}",
                        channel, method, status, attempt, result.getDurationMs());
                return result;
            } catch (HttpTimeoutException e) {
                lastError = OutboundResult.error("TIMEOUT", attempt, elapsedMs(started));
                if (attempt < maxAttempts) {
                    if (!backoff()) {
                        return interrupted(started, attempt);
                    }
                    continue;
                }
            } catch (IOException e) {
                lastError = OutboundResult.error("IO", attempt, elapsedMs(started));
                if (attempt < maxAttempts) {
                    if (!backoff()) {
                        return interrupted(started, attempt);
                    }
                    continue;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (enabled && circuitEnabled && breaker != null) {
                    breaker.onFailure();
                }
                log.warn("出站调用被中断 channel={} method={}", channel, method);
                return OutboundResult.error("INTERRUPTED", attempt, elapsedMs(started));
            }
        }

        if (enabled && circuitEnabled && breaker != null) {
            breaker.onFailure();
        }
        OutboundResult result = lastError == null
                ? OutboundResult.error("UNKNOWN", maxAttempts, elapsedMs(started))
                : lastError;
        log.warn("出站调用失败 channel={} method={} errorKind={} attempts={} durationMs={}",
                channel, method, result.getErrorKind(), result.getAttempts(), result.getDurationMs());
        return result;
    }

    private HttpRequest buildRequest(String method, String url, String body, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(policy.getReadTimeout());
        boolean hasContentType = false;
        for (Map.Entry<String, String> header : (headers == null ? Map.<String, String>of() : headers).entrySet()) {
            builder.header(header.getKey(), header.getValue());
            if ("Content-Type".equalsIgnoreCase(header.getKey())) {
                hasContentType = true;
            }
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            if (!hasContentType) {
                builder.header("Content-Type", "application/json; charset=utf-8");
            }
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private boolean backoff() {
        if (policy.getRetryBackoff() == null || policy.getRetryBackoff().isZero()) {
            return true;
        }
        try {
            sleeper.sleep(policy.getRetryBackoff());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private OutboundResult interrupted(long started, int attempt) {
        if (enabled && circuitEnabled && breaker != null) {
            breaker.onFailure();
        }
        return OutboundResult.error("INTERRUPTED", attempt, elapsedMs(started));
    }

    private void recordOutcome(int status) {
        if (!enabled || !circuitEnabled || breaker == null) {
            return;
        }
        if (status == 429 || status >= 500) {
            breaker.onFailure();
        } else {
            breaker.onSuccess();
        }
    }

    private static boolean retryableStatus(int status) {
        return status == 429 || status >= 500;
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }

    private static String safeUrl(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
}
