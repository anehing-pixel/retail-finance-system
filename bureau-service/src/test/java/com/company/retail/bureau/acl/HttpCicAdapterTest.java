package com.company.retail.bureau.acl;

import com.company.retail.bureau.domain.CreditBureau;
import com.company.retail.common.outbound.OutboundHttpClientFactory;
import com.company.retail.common.outbound.OutboundProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-7：CIC 适配器走共享出站客户端；查询可配置重试，写入绝不重试；
 * 同一业务对象的重发共享稳定幂等键。
 */
class HttpCicAdapterTest {

    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("\"idempotencyKey\"\\s*:\\s*\"([^\"]+)\"");

    private final AtomicInteger hits = new AtomicInteger();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Integer>> statusPlans = new ConcurrentHashMap<>();
    private final Map<String, String> responseBodies = new ConcurrentHashMap<>();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpCicAdapter adapter(OutboundProperties props) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cic/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Deque<Integer> plan = statusPlans.get(path);
            int status = plan == null || plan.isEmpty() ? 200 : plan.pollFirst();
            byte[] body = responseBodies.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new HttpCicAdapter(url, new OutboundHttpClientFactory(props));
    }

    private void plan(String path, int... statuses) {
        Deque<Integer> deque = new ArrayDeque<>();
        for (int status : statuses) {
            deque.addLast(status);
        }
        statusPlans.put(path, deque);
    }

    private void respond(String path, String body) {
        responseBodies.put(path, body);
    }

    @Test
    void inquiryCompleted() throws IOException {
        respond("/cic/v1/inquiries", "{\"code\":\"OK\",\"data\":{\"inquiryRef\":\"INQ-1\",\"costAmount\":12}}");
        HttpCicAdapter adapter = adapter(new OutboundProperties());

        InquiryOutcome outcome = adapter.inquire(
                new BureauInquiryCommand(100L, 200L, CreditBureau.CIC, "CREDIT_ASSESSMENT"));

        assertEquals("COMPLETED", outcome.status());
        assertEquals("INQ-1", outcome.resultSummary());
        assertEquals(12L, outcome.costAmount());
    }

    @Test
    void inquiryFailedOnServerError() throws IOException {
        plan("/cic/v1/inquiries", 500);
        HttpCicAdapter adapter = adapter(new OutboundProperties());

        InquiryOutcome outcome = adapter.inquire(
                new BureauInquiryCommand(100L, 200L, CreditBureau.CIC, "CREDIT_ASSESSMENT"));

        assertEquals("FAILED", outcome.status());
        assertEquals(1, hits.get(), "QUERY 默认不重试");
    }

    @Test
    void inquiryRetriesWhenConfigured() throws IOException {
        plan("/cic/v1/inquiries", 500, 200);
        respond("/cic/v1/inquiries", "{\"code\":\"OK\",\"data\":{\"inquiryRef\":\"INQ-2\",\"costAmount\":1}}");
        OutboundProperties props = new OutboundProperties();
        props.getQuery().setMaxAttempts(2);
        HttpCicAdapter adapter = adapter(props);

        InquiryOutcome outcome = adapter.inquire(
                new BureauInquiryCommand(100L, 200L, CreditBureau.CIC, "CREDIT_ASSESSMENT"));

        assertEquals("COMPLETED", outcome.status());
        assertEquals(2, hits.get());
    }

    @Test
    void inquiryUsesStableIdempotencyKey() throws IOException {
        respond("/cic/v1/inquiries", "{\"code\":\"OK\",\"data\":{\"inquiryRef\":\"INQ-1\",\"costAmount\":0}}");
        HttpCicAdapter adapter = adapter(new OutboundProperties());

        adapter.inquire(new BureauInquiryCommand(100L, 200L, CreditBureau.CIC, "P"));
        adapter.inquire(new BureauInquiryCommand(100L, 200L, CreditBureau.CIC, "P"));
        adapter.inquire(new BureauInquiryCommand(101L, 200L, CreditBureau.CIC, "P"));

        List<String> keys = bodies.stream().map(this::keyOf).toList();
        assertEquals(keys.get(0), keys.get(1));
        assertFalse(keys.get(0).equals(keys.get(2)));
        assertTrue(keys.get(0).startsWith("CIC-INQ:100:200"));
    }

    @Test
    void reportSentAndRejected() throws IOException {
        respond("/cic/v1/reports", "{\"code\":\"OK\",\"data\":{\"reportRef\":\"RPT-1\"}}");
        HttpCicAdapter adapter = adapter(new OutboundProperties());

        ReportOutcome sent = adapter.submitReport(
                new BureauReportCommand(10L, 20L, CreditBureau.CIC, "MONTHLY", "2026-11-24"));
        assertEquals("SENT", sent.status());
        assertEquals("RPT-1", sent.responseRef());

        plan("/cic/v1/reports", 500);
        ReportOutcome rejected = adapter.submitReport(
                new BureauReportCommand(10L, 20L, CreditBureau.CIC, "MONTHLY", "2026-11-24"));
        assertEquals("REJECTED", rejected.status());
    }

    @Test
    void writeDoesNotRetryEvenWhenConfigured() throws IOException {
        plan("/cic/v1/reports", 500, 200);
        OutboundProperties props = new OutboundProperties();
        props.getWrite().setMaxAttempts(3);
        HttpCicAdapter adapter = adapter(props);

        ReportOutcome outcome = adapter.submitReport(
                new BureauReportCommand(10L, 20L, CreditBureau.CIC, "MONTHLY", "2026-11-24"));

        assertEquals("REJECTED", outcome.status());
        assertEquals(1, hits.get(), "写入动作绝不自动重试");
    }

    @Test
    void disputeAccepted() throws IOException {
        respond("/cic/v1/disputes", "{\"code\":\"OK\",\"data\":{\"accepted\":true,\"disputeRef\":\"DSP-1\"}}");
        HttpCicAdapter adapter = adapter(new OutboundProperties());

        DisputeOutcome outcome = adapter.fileDispute(
                new BureauDisputeCommand(77L, CreditBureau.CIC, "C-1"));

        assertTrue(outcome.accepted());
        assertEquals("DSP-1", outcome.registryRef());
    }

    private String keyOf(String body) {
        Matcher matcher = IDEMPOTENCY_KEY.matcher(body);
        return matcher.find() ? matcher.group(1) : "";
    }
}
