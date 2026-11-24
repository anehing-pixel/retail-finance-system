package com.company.retail.core.integration;

import com.company.retail.common.outbound.OutboundHttpClientFactory;
import com.company.retail.common.outbound.OutboundProfile;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-7：银行代付（FINANCIAL）绝不自动重试，且同一指令的重发共享稳定幂等键。
 */
class HttpBankPayAdapterTest {

    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("\"idempotencyKey\"\\s*:\\s*\"([^\"]+)\"");

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpBankPayAdapter adapter(int status, String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bank/v1/disbursements", exchange -> {
            hits.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var client = OutboundHttpClientFactory.defaults().client("bank-pay", OutboundProfile.FINANCIAL);
        return new HttpBankPayAdapter(url, client);
    }

    @Test
    void returnsPaidWithBankRef() throws IOException {
        HttpBankPayAdapter adapter = adapter(200, "{\"bankRef\":\"BANK-1\",\"status\":\"PAID\"}");

        BankPayAdapter.PayResult result = adapter.pay("INS-1", 100_000L, "ACC-1");

        assertTrue(result.paid());
        assertEquals("BANK-1", result.bankRef());
    }

    @Test
    void rejectsNonPaidStatus() throws IOException {
        HttpBankPayAdapter adapter = adapter(200, "{\"status\":\"REJECTED\"}");

        BankPayAdapter.PayResult result = adapter.pay("INS-2", 100_000L, "ACC-1");

        assertFalse(result.paid());
        assertTrue(result.rawMessage().contains("BANK_STATUS_REJECTED"));
    }

    @Test
    void doesNotRetryServerError() throws IOException {
        HttpBankPayAdapter adapter = adapter(500, "{\"error\":\"boom\"}");

        BankPayAdapter.PayResult result = adapter.pay("INS-3", 100_000L, "ACC-1");

        assertFalse(result.paid());
        assertEquals(1, hits.get(), "资金动作绝不自动重试");
    }

    @Test
    void reusesStableIdempotencyKeyForSameInstruction() throws IOException {
        HttpBankPayAdapter adapter = adapter(200, "{\"bankRef\":\"BANK-1\",\"status\":\"PAID\"}");

        adapter.pay("INS-SAME", 100_000L, "ACC-1");
        adapter.pay("INS-SAME", 100_000L, "ACC-1");
        adapter.pay("INS-OTHER", 100_000L, "ACC-1");

        List<String> keys = bodies.stream().map(body -> {
            Matcher matcher = IDEMPOTENCY_KEY.matcher(body);
            return matcher.find() ? matcher.group(1) : "";
        }).toList();

        assertEquals(keys.get(0), keys.get(1));
        assertFalse(keys.get(0).equals(keys.get(2)));
        assertTrue(keys.get(0).startsWith("BANK-PAY:INS-SAME"));
    }

    @Test
    void unreachableReturnsNotPaid() throws IOException {
        HttpBankPayAdapter adapter = adapter(200, "{\"status\":\"PAID\"}");
        server.stop(0);
        server = null;

        BankPayAdapter.PayResult result = adapter.pay("INS-4", 100_000L, "ACC-1");

        assertFalse(result.paid());
    }
}
