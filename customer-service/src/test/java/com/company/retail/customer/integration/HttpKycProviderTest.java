package com.company.retail.customer.integration;

import com.company.retail.common.outbound.OutboundHttpClientFactory;
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
 * P0-7：eKYC 咨询性通道——单次尝试、失败返回 null、稳定幂等键。
 */
class HttpKycProviderTest {

    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("\"idempotencyKey\"\\s*:\\s*\"([^\"]+)\"");

    private final AtomicInteger hits = new AtomicInteger();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private volatile Duration delay = Duration.ZERO;
    private volatile int status = 200;
    private volatile String responseBody = "{\"providerRef\":\"K-1\",\"status\":\"PASS\"}";

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpKycProvider provider(OutboundProperties props) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/kyc/v1/verifications", exchange -> {
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
        var client = new OutboundHttpClientFactory(props).client("kyc", com.company.retail.common.outbound.OutboundProfile.ADVISORY);
        return new HttpKycProvider(url, client);
    }

    @Test
    void returnsSubmissionOnSuccess() throws IOException {
        HttpKycProvider provider = provider(new OutboundProperties());

        KycProvider.KycSubmission submission = provider.submit(1L, "****9999", "DRIVER_LICENSE");

        assertEquals("K-1", submission.providerRef());
        assertEquals("PASS", submission.status());
        assertEquals("EKYC-HTTP", submission.providerName());
    }

    @Test
    void returnsNullOnServerErrorWithoutRetry() throws IOException {
        status = 500;
        HttpKycProvider provider = provider(new OutboundProperties());

        assertNull(provider.submit(2L, "****9999", "DRIVER_LICENSE"));
        assertEquals(1, hits.get());
    }

    @Test
    void returnsNullOnTimeout() throws IOException {
        delay = Duration.ofSeconds(1);
        OutboundProperties props = new OutboundProperties();
        props.getAdvisory().setReadTimeout(Duration.ofMillis(200));
        props.getAdvisory().setConnectTimeout(Duration.ofMillis(200));
        HttpKycProvider provider = provider(props);

        assertNull(provider.submit(3L, "****9999", "DRIVER_LICENSE"));
    }

    @Test
    void reusesStableIdempotencyKeyForSameVerification() throws IOException {
        HttpKycProvider provider = provider(new OutboundProperties());

        provider.submit(9L, "****9999", "DRIVER_LICENSE");
        provider.submit(9L, "****9999", "DRIVER_LICENSE");
        provider.submit(10L, "****9999", "DRIVER_LICENSE");

        List<String> keys = bodies.stream().map(body -> {
            Matcher matcher = IDEMPOTENCY_KEY.matcher(body);
            return matcher.find() ? matcher.group(1) : "";
        }).toList();

        assertEquals(keys.get(0), keys.get(1));
        assertFalse(keys.get(0).equals(keys.get(2)));
        assertEquals("KYC:9", keys.get(0));
    }
}
