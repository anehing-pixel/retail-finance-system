package com.company.retail.common.contract;

import com.company.retail.common.outbound.OutboundHttpClientFactory;
import com.company.retail.common.outbound.OutboundProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-7：同意门禁 raw 兜底走 GATE_RAW——补连接超时 + 熔断，语义仍 fail-closed。
 */
class ConsentGateRawFallbackTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private volatile String responseBody = "{\"code\":\"OK\",\"data\":{\"allowed\":true}}";
    private volatile Duration delay = Duration.ZERO;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ConsentGateFacade facade(OutboundProperties props) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/consents/", exchange -> {
            hits.incrementAndGet();
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
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        FeignContracts.ConsentGateClient failingFeign = consentId -> {
            throw new RuntimeException("simulated feign decode failure");
        };
        return new ConsentGateFacade(failingFeign, url, null, new OutboundHttpClientFactory(props));
    }

    @Test
    void rawFallbackAllowsWhen200AllowedTrue() throws IOException {
        ConsentGateFacade facade = facade(new OutboundProperties());

        assertTrue(facade.isConsentValid(1L));
        assertTrue(hits.get() > 0);
    }

    @Test
    void rawFallbackRejectsWhenAllowedFalse() throws IOException {
        responseBody = "{\"code\":\"OK\",\"data\":{\"allowed\":false}}";
        ConsentGateFacade facade = facade(new OutboundProperties());

        assertFalse(facade.isConsentValid(1L));
    }

    @Test
    void rawFallbackRejectsOnServerError() throws IOException {
        status = 500;
        ConsentGateFacade facade = facade(new OutboundProperties());

        assertFalse(facade.isConsentValid(1L));
    }

    @Test
    void rawFallbackRejectsOnTimeout() throws IOException {
        delay = Duration.ofSeconds(1);
        OutboundProperties props = new OutboundProperties();
        props.getGateRaw().setReadTimeout(Duration.ofMillis(200));
        props.getGateRaw().setConnectTimeout(Duration.ofMillis(200));
        ConsentGateFacade facade = facade(props);

        assertFalse(facade.isConsentValid(1L));
    }

    @Test
    void rawFallbackWithoutBaseUrlRejectsWithoutCalling() {
        FeignContracts.ConsentGateClient failingFeign = consentId -> {
            throw new RuntimeException("simulated feign decode failure");
        };
        ConsentGateFacade facade = new ConsentGateFacade(failingFeign, "");

        assertFalse(facade.isConsentValid(2L));
    }
}
