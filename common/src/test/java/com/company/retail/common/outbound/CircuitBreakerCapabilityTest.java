package com.company.retail.common.outbound;

import feign.Client;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P0-7：Feign 客户端级熔断（按门禁路径隔离）。
 */
class CircuitBreakerCapabilityTest {

    private final OutboundPolicy policy = OutboundPolicy.of(
            Duration.ofMillis(500), Duration.ofSeconds(2), 2, Duration.ZERO,
            1, Duration.ofSeconds(30));

    private static Request request(String path) {
        return Request.create(Request.HttpMethod.GET, path, Map.of(), null, StandardCharsets.UTF_8);
    }

    private static Client fixedStatusClient(AtomicInteger hits, int status) {
        return (request, options) -> {
            hits.incrementAndGet();
            return Response.builder()
                    .status(status)
                    .reason("test")
                    .request(request)
                    .headers(Map.of())
                    .body(new byte[0])
                    .build();
        };
    }

    @Test
    void successfulCallPassesThrough() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        Client wrapped = new CircuitBreakerCapability(new OutboundCircuitBreakerRegistry(), policy)
                .enrich(fixedStatusClient(hits, 200));

        Response response = wrapped.execute(request("/api/contracts/1/disbursement-gate"), new Request.Options());

        assertEquals(200, response.status());
        assertEquals(1, hits.get());
    }

    @Test
    void failureOpensBreakerAndSkipsDelegate() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        Client wrapped = new CircuitBreakerCapability(new OutboundCircuitBreakerRegistry(), policy)
                .enrich(fixedStatusClient(hits, 500));

        assertEquals(500, wrapped.execute(request("/api/contracts/1/disbursement-gate"), new Request.Options()).status());
        assertThrows(CircuitOpenException.class,
                () -> wrapped.execute(request("/api/contracts/1/disbursement-gate"), new Request.Options()));
        assertEquals(1, hits.get(), "熔断打开后不应再调用 delegate");
    }

    @Test
    void gatePathsUseIndependentBreakers() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        Client wrapped = new CircuitBreakerCapability(new OutboundCircuitBreakerRegistry(), policy)
                .enrich(fixedStatusClient(hits, 500));

        assertEquals(500, wrapped.execute(request("/api/contracts/1/disbursement-gate"), new Request.Options()).status());
        assertThrows(CircuitOpenException.class,
                () -> wrapped.execute(request("/api/contracts/1/disbursement-gate"), new Request.Options()));
        // 另一个门禁使用独立 breaker，仍应放行
        assertEquals(500, wrapped.execute(request("/api/consents/2/disbursement-gate"), new Request.Options()).status());
        assertEquals(2, hits.get());
    }

    @Test
    void ioExceptionRecordsFailureAndPropagates() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        Client failing = (request, options) -> {
            hits.incrementAndGet();
            throw new IOException("connect timed out");
        };
        Client wrapped = new CircuitBreakerCapability(new OutboundCircuitBreakerRegistry(), policy)
                .enrich(failing);

        assertThrows(IOException.class,
                () -> wrapped.execute(request("/api/underwriting/cases/3/disbursement-gate"), new Request.Options()));
        assertThrows(CircuitOpenException.class,
                () -> wrapped.execute(request("/api/underwriting/cases/3/disbursement-gate"), new Request.Options()));
        assertEquals(1, hits.get());
    }
}
