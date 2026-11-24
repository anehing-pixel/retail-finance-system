package com.company.retail.common.outbound;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-7：JDK HttpClient 统一封装（真实 HTTP 桩；不 sleep、不启 Spring）。
 */
class OutboundHttpClientTest {

    private StubServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    private OutboundHttpClient client(int maxAttempts, int threshold, boolean circuitEnabled) {
        OutboundPolicy policy = OutboundPolicy.of(Duration.ofMillis(500), Duration.ofMillis(300),
                maxAttempts, Duration.ofMillis(1), threshold, Duration.ofSeconds(30));
        HttpClient http = HttpClient.newBuilder().connectTimeout(policy.getConnectTimeout()).build();
        return new OutboundHttpClient("test", policy,
                new OutboundCircuitBreaker(threshold, Duration.ofSeconds(30)),
                true, circuitEnabled, http, duration -> { });
    }

    @Test
    void getReturnsBodyAndStatus() throws IOException {
        server = new StubServer(200);

        OutboundResult result = client(1, 5, true).get(server.url("/test"), Map.of());

        assertEquals(200, result.getStatus());
        assertEquals(1, result.getAttempts());
        assertTrue(result.is2xx());
        assertFalse(result.failed());
        assertEquals("{\"ok\":true}", result.getBody());
        assertEquals(1, server.hits.get());
    }

    @Test
    void retriesRetryableStatusThenSucceeds() throws IOException {
        server = new StubServer(503, 200);

        OutboundResult result = client(2, 5, true).get(server.url("/test"), Map.of());

        assertEquals(200, result.getStatus());
        assertEquals(2, result.getAttempts());
        assertEquals(2, server.hits.get());
    }

    @Test
    void doesNotRetryClientError() throws IOException {
        server = new StubServer(400);

        OutboundResult result = client(3, 5, true).get(server.url("/test"), Map.of());

        assertEquals(400, result.getStatus());
        assertEquals(1, result.getAttempts());
        assertEquals(1, server.hits.get(), "4xx 是确定性拒绝，不应重试");
    }

    @Test
    void persistentServerErrorExhaustsAttempts() throws IOException {
        server = new StubServer(503);

        OutboundResult result = client(2, 5, true).get(server.url("/test"), Map.of());

        assertEquals(503, result.getStatus());
        assertEquals(2, result.getAttempts());
        assertEquals(2, server.hits.get());
        assertTrue(result.failed());
    }

    @Test
    void timeoutReturnsStructuredError() throws IOException {
        server = new StubServer(Duration.ofSeconds(2), 200);

        OutboundResult result = client(1, 5, true).get(server.url("/test"), Map.of());

        assertEquals(-1, result.getStatus());
        assertEquals("TIMEOUT", result.getErrorKind());
        assertTrue(result.failed());
        assertFalse(result.is2xx());
    }

    @Test
    void circuitOpensAfterThresholdAndSkipsDownstream() throws IOException {
        server = new StubServer(500);
        OutboundHttpClient client = client(1, 1, true);

        OutboundResult first = client.get(server.url("/test"), Map.of());
        OutboundResult second = client.get(server.url("/test"), Map.of());

        assertEquals(500, first.getStatus());
        assertTrue(second.isCircuitOpen());
        assertEquals(-1, second.getStatus());
        assertEquals(1, server.hits.get(), "熔断打开后不应再打对端");
    }

    @Test
    void disabledOutboundDoesNotRetryOrBreakCircuit() throws IOException {
        server = new StubServer(503);
        OutboundPolicy policy = OutboundPolicy.of(Duration.ofMillis(500), Duration.ofMillis(300),
                3, Duration.ofMillis(1), 1, Duration.ofSeconds(30));
        HttpClient http = HttpClient.newBuilder().connectTimeout(policy.getConnectTimeout()).build();
        OutboundHttpClient client = new OutboundHttpClient("test", policy,
                new OutboundCircuitBreaker(1, Duration.ofSeconds(30)),
                false, true, http, duration -> { });

        OutboundResult result = client.get(server.url("/test"), Map.of());

        assertEquals(1, result.getAttempts());
        assertEquals(1, server.hits.get());
        assertFalse(result.isCircuitOpen());
    }

    @Test
    void postJsonSendsHeadersAndBody() throws IOException {
        server = new StubServer(200);

        OutboundResult result = client(1, 5, true).postJson(server.url("/test"),
                "{\"a\":1}", Map.of("Idempotency-Key", "KEY-1", "X-Test", "yes"));

        assertEquals(200, result.getStatus());
        assertEquals("{\"a\":1}", server.lastBody.get());
        assertEquals("KEY-1", server.lastHeaders.get().getFirst("Idempotency-Key"));
        assertEquals("yes", server.lastHeaders.get().getFirst("X-Test"));
        assertTrue(server.lastHeaders.get().getFirst("Content-Type").contains("application/json"));
    }

    @Test
    void interruptedRetryRestoresInterruptFlag() throws IOException {
        server = new StubServer(503);
        OutboundPolicy policy = OutboundPolicy.of(Duration.ofMillis(500), Duration.ofMillis(300),
                2, Duration.ofMillis(1), 5, Duration.ofSeconds(30));
        HttpClient http = HttpClient.newBuilder().connectTimeout(policy.getConnectTimeout()).build();
        OutboundHttpClient.Sleeper interrupting = duration -> {
            throw new InterruptedException("test");
        };
        OutboundHttpClient client = new OutboundHttpClient("test", policy,
                new OutboundCircuitBreaker(5, Duration.ofSeconds(30)),
                true, true, http, interrupting);

        OutboundResult result = client.get(server.url("/test"), Map.of());

        assertEquals("INTERRUPTED", result.getErrorKind());
        assertTrue(Thread.currentThread().isInterrupted(), "中断标志必须恢复");
        Thread.interrupted();
    }

    /** 极简 HTTP 桩：按状态序列响应；可捕获请求体与头。 */
    private static final class StubServer implements AutoCloseable {

        private final HttpServer server;
        private final AtomicInteger hits = new AtomicInteger();
        private final AtomicReference<String> lastBody = new AtomicReference<>();
        private final AtomicReference<com.sun.net.httpserver.Headers> lastHeaders = new AtomicReference<>();

        StubServer(int... statuses) throws IOException {
            this(Duration.ZERO, statuses);
        }

        StubServer(Duration delay, int... statuses) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/test", exchange -> {
                int index = hits.getAndIncrement();
                lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                lastHeaders.set(exchange.getRequestHeaders());
                if (!delay.isZero()) {
                    try {
                        Thread.sleep(delay.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                int status = index < statuses.length ? statuses[index] : statuses[statuses.length - 1];
                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
