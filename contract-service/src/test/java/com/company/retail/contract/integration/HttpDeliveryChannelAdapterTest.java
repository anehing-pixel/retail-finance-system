package com.company.retail.contract.integration;

import com.company.retail.common.outbound.OutboundHttpClientFactory;
import com.company.retail.common.outbound.OutboundProfile;
import com.company.retail.common.outbound.OutboundProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P0-7：送达通道（ADVISORY）单次尝试、失败返回 null、稳定幂等键。
 */
class HttpDeliveryChannelAdapterTest {

    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("\"idempotencyKey\"\\s*:\\s*\"([^\"]+)\"");

    private final AtomicInteger hits = new AtomicInteger();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private volatile int status = 200;
    private volatile String responseBody = "{\"receiptRef\":\"R-1\"}";
    private volatile Duration delay = Duration.ZERO;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpDeliveryChannelAdapter adapter(OutboundProperties props) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notify/v1/deliveries", exchange -> {
            hits.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
        var client = new OutboundHttpClientFactory(props).client("delivery", OutboundProfile.ADVISORY);
        return new HttpDeliveryChannelAdapter(url, client);
    }

    @Test
    void returnsReceiptOnSuccess() throws IOException {
        HttpDeliveryChannelAdapter adapter = adapter(new OutboundProperties());

        assertEquals("R-1", adapter.notifyDelivered(1L, "CONTRACT_DOCUMENT", "POSTAL_MAIL", "t-1"));
    }

    @Test
    void returnsNullOnServerErrorWithoutRetry() throws IOException {
        status = 500;
        HttpDeliveryChannelAdapter adapter = adapter(new OutboundProperties());

        assertNull(adapter.notifyDelivered(1L, "CONTRACT_DOCUMENT", "POSTAL_MAIL", "t-1"));
        assertEquals(1, hits.get());
    }

    @Test
    void returnsNullOnTimeout() throws IOException {
        delay = Duration.ofSeconds(1);
        OutboundProperties props = new OutboundProperties();
        props.getAdvisory().setReadTimeout(Duration.ofMillis(200));
        props.getAdvisory().setConnectTimeout(Duration.ofMillis(200));
        HttpDeliveryChannelAdapter adapter = adapter(props);

        assertNull(adapter.notifyDelivered(1L, "CONTRACT_DOCUMENT", "POSTAL_MAIL", "t-1"));
    }

    @Test
    void reusesStableIdempotencyKeyForSameDelivery() throws IOException {
        HttpDeliveryChannelAdapter adapter = adapter(new OutboundProperties());

        adapter.notifyDelivered(7L, "CONTRACT_DOCUMENT", "POSTAL_MAIL", "t-1");
        adapter.notifyDelivered(7L, "CONTRACT_DOCUMENT", "POSTAL_MAIL", "t-1");
        adapter.notifyDelivered(7L, "COOLING_OFF", "POSTAL_MAIL", "t-1");

        List<String> keys = bodies.stream().map(body -> {
            Matcher matcher = IDEMPOTENCY_KEY.matcher(body);
            return matcher.find() ? matcher.group(1) : "";
        }).toList();

        assertEquals(keys.get(0), keys.get(1));
        assertFalse(keys.get(0).equals(keys.get(2)));
        assertEquals("CTR-DELIVERY:7:CONTRACT_DOCUMENT:POSTAL_MAIL:t-1", keys.get(0));
    }
}
