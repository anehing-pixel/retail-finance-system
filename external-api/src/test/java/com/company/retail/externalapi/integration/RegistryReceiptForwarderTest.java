package com.company.retail.externalapi.integration;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.outbound.OutboundHttpClientFactory;
import com.company.retail.common.outbound.OutboundProperties;
import com.company.retail.common.outbound.OutboundProfile;
import com.company.retail.domain.exception.RFErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-7：DMZ 回调转发（FORWARD）锁内不重试；上游非 2xx 保留原响应语义；
 * 传输失败收敛为 RF_SYS_001 + TITLE_RELEASE_UNREACHABLE。
 */
class RegistryReceiptForwarderTest {

    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastIdempotencyKey = new AtomicReference<>();
    private HttpServer server;
    private volatile int status = 200;
    private volatile String responseBody = "{\"code\":\"OK\",\"data\":{\"x\":1}}";
    private volatile Duration delay = Duration.ZERO;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private RegistryReceiptForwarder forwarder(OutboundProperties props) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/release-orders/", exchange -> {
            hits.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            if (!delay.isZero()) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var client = new OutboundHttpClientFactory(props)
                .noRetryClient("title-release-forward", OutboundProfile.FORWARD);
        return new RegistryReceiptForwarder(client);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void forwardsAndReturnsUpstreamBody() throws IOException {
        RegistryReceiptForwarder forwarder = forwarder(new OutboundProperties());

        RegistryReceiptForwarder.Outcome outcome = forwarder.forward(
                url(), 9L, "Bearer token", Map.of("authority", "EXTERNAL_REGISTRY"), "REG-RCPT:9:R-1");

        assertEquals(200, outcome.status());
        assertEquals(1, outcome.body().path("data").path("x").asInt());
        assertEquals("REG-RCPT:9:R-1", lastIdempotencyKey.get());
        assertTrue(lastBody.get().contains("EXTERNAL_REGISTRY"));
    }

    @Test
    void preservesUpstreamErrorStatusWithoutThrowing() throws IOException {
        status = 500;
        responseBody = "{\"code\":\"RF_SYS_001\",\"message\":\"boom\"}";
        RegistryReceiptForwarder forwarder = forwarder(new OutboundProperties());

        RegistryReceiptForwarder.Outcome outcome = forwarder.forward(
                url(), 9L, "Bearer token", Map.of("authority", "EXTERNAL_REGISTRY"), "REG-RCPT:9:R-1");

        assertEquals(500, outcome.status());
        assertEquals(1, hits.get(), "锁内转发绝不重试");
    }

    @Test
    void timeoutBecomesSysError() throws IOException {
        delay = Duration.ofSeconds(1);
        OutboundProperties props = new OutboundProperties();
        props.getForward().setReadTimeout(Duration.ofMillis(200));
        props.getForward().setConnectTimeout(Duration.ofMillis(200));
        RegistryReceiptForwarder forwarder = forwarder(props);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> forwarder.forward(url(), 9L, "Bearer token",
                        Map.of("authority", "EXTERNAL_REGISTRY"), "REG-RCPT:9:R-1"));

        assertEquals(RFErrorCode.RF_SYS_001, exception.getErrorCode());
        assertEquals("TITLE_RELEASE_UNREACHABLE", exception.getDetails().get("reason"));
    }
}
