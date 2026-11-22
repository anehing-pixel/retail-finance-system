package com.company.retail.bureau.application;

import com.company.platform.event.DomainEvent;
import com.company.retail.bureau.application.BureauReportRepository.ReportRow;
import com.company.retail.bureau.application.TestFixtures.InMemoryBureauTriggerRepository;
import com.company.retail.bureau.application.TestFixtures.InMemoryConsentAnchor;
import com.company.retail.bureau.application.TestFixtures.InMemoryMessageLogRepository;
import com.company.retail.bureau.application.TestFixtures.InMemoryReportRepository;
import com.company.retail.bureau.application.TestFixtures.RecordingOutboxPublisher;
import com.company.retail.bureau.domain.CreditBureau;
import com.company.retail.bureau.domain.DefaultCreditBureauReportingPolicy;
import com.company.retail.common.contract.ConsentGateFacade;
import com.company.retail.common.contract.StubConsentGateClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件消费方单测（W9-A）：DPD_CHANGED / LOAN_SETTLED 消费 → 报送闭环；
 * 消费幂等（重复投递不重复报送）、坏 payload 不吞异常（R09 §5.4）。
 */
class BureauReportConsumersTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    private InMemoryReportRepository reportRepository;
    private InMemoryBureauTriggerRepository triggerPort;
    private InMemoryConsentAnchor consentAnchor;
    private InMemoryMessageLogRepository messageLog;
    private RecordingOutboxPublisher outbox;
    private StubConsentGateClient consentClient;
    private BureauReportExecutionService executionService;
    private DpdChangedReportConsumer dpdConsumer;
    private LoanSettledReportConsumer settledConsumer;

    @BeforeEach
    void setUp() {
        reportRepository = new InMemoryReportRepository();
        triggerPort = new InMemoryBureauTriggerRepository();
        consentAnchor = new InMemoryConsentAnchor();
        messageLog = new InMemoryMessageLogRepository();
        outbox = new RecordingOutboxPublisher();
        consentClient = new StubConsentGateClient(StubConsentGateClient.Mode.ALLOW);
        var guard = new ConsentGateGuard(new ConsentGateFacade(consentClient, ""));
        var policy = new DefaultCreditBureauReportingPolicy(true, "CIC,JICC", "CIC", 30, 1);
        var adapters = Map.of(
                CreditBureau.CIC, new com.company.retail.bureau.acl.CicStubAdapter(),
                CreditBureau.JICC, new com.company.retail.bureau.acl.JiccStubAdapter());
        var reportAppService = new BureauReportAppService(reportRepository, messageLog, adapters,
                policy, guard, outbox);
        executionService = new BureauReportExecutionService(reportAppService, reportRepository,
                triggerPort, consentAnchor);
        dpdConsumer = new DpdChangedReportConsumer(triggerPort, executionService);
        settledConsumer = new LoanSettledReportConsumer(executionService);
    }

    private static DomainEvent event(long eventId, String type, String aggregateKey, String payload) {
        return new DomainEvent(eventId, type, aggregateKey, payload, Instant.now());
    }

    // ---- DPD_CHANGED：触发登记 + 事件 = 报送闭环 ----

    @Test
    void dpdChanged_withRegisteredTrigger_executesReportAndConsumesTrigger() {
        triggerPort.insert(301L, "M1", "DELINQUENCY", 5001L, TODAY);

        dpdConsumer.consume(event(1, "DPD_CHANGED", "301",
                "{\"loanId\":301,\"stage\":\"M1\",\"bizDate\":\"2026-11-23\",\"traceId\":\"t1\"}"));

        ReportRow row = reportRepository.findByLoanAndTrigger(301L, "DELINQUENCY").orElseThrow();
        assertEquals("SENT", row.status());
        assertEquals("CONSUMED", triggerPort.store.values().iterator().next().status());
        assertEquals(1, outbox.published.size());
        assertEquals("CREDIT_BUREAU_REPORT_SENT", outbox.published.get(0).eventType());
        assertEquals(String.valueOf(row.reportId()), outbox.published.get(0).aggregateKey());
        assertTrue(messageLog.countByBizRef(String.valueOf(row.reportId())) >= 2);
    }

    @Test
    void dpdChanged_duplicateDelivery_doesNotReportTwice() {
        // 消费幂等（R09 §6）：at-least-once 重复投递 → 只报送一次
        triggerPort.insert(302L, "M1", "DELINQUENCY", 5001L, TODAY);
        var event = event(2, "DPD_CHANGED", "302",
                "{\"loanId\":302,\"stage\":\"M1\",\"traceId\":\"t2\"}");
        dpdConsumer.consume(event);
        dpdConsumer.consume(event); // 同一事件重放（event_id 相同，event_consumptions 幂等挡）

        assertEquals(1, outbox.published.size());
        assertEquals(1, reportRepository.store.values().stream()
                .filter(r -> r.loanId() == 302L).count());
    }

    @Test
    void dpdChanged_withoutRegisteredTrigger_isNoOp() {
        // W7 未登记触发（非逾期债权/已消费）：事件到达不产生报送
        dpdConsumer.consume(event(3, "DPD_CHANGED", "303",
                "{\"loanId\":303,\"stage\":\"M2\",\"traceId\":\"t3\"}"));
        assertEquals(0, outbox.published.size());
        assertEquals(0, reportRepository.store.size());
    }

    @Test
    void dpdChanged_missingLoanId_throwsNotSwallowed() {
        // 铁律 2：缺业务主键直接抛（调度器计数 → 3 次 DEAD），不吞
        assertThrows(IllegalStateException.class,
                () -> dpdConsumer.consume(event(4, "DPD_CHANGED", "304", "{\"stage\":\"M1\"}")));
    }

    // ---- LOAN_SETTLED：结清报送 ----

    @Test
    void loanSettled_reportsSettled_viaSameExecutionChannel() {
        consentAnchor.put(401L, 5001L);

        settledConsumer.consume(event(5, "LOAN_SETTLED", "401",
                "{\"loanId\":401,\"contractId\":21,\"status\":\"CLOSED\",\"traceId\":\"t5\"}"));

        ReportRow row = reportRepository.findByLoanAndTrigger(401L, "SETTLED").orElseThrow();
        assertEquals("SENT", row.status());
        assertEquals(1, outbox.published.size());
        assertEquals("CREDIT_BUREAU_REPORT_SENT", outbox.published.get(0).eventType());
        assertEquals(String.valueOf(row.reportId()), outbox.published.get(0).aggregateKey());
    }

    @Test
    void loanSettled_duplicateDelivery_idempotent() {
        consentAnchor.put(402L, 5001L);
        var event = event(6, "LOAN_SETTLED", "402", "{\"loanId\":402,\"traceId\":\"t6\"}");
        settledConsumer.consume(event);
        settledConsumer.consume(event);

        assertEquals(1, outbox.published.size());
        assertEquals(1, reportRepository.store.values().stream()
                .filter(r -> r.loanId() == 402L).count());
    }

    @Test
    void loanSettled_missingLoanId_throwsNotSwallowed() {
        assertThrows(IllegalStateException.class,
                () -> settledConsumer.consume(event(7, "LOAN_SETTLED", "403", "{}")));
    }

    @Test
    void consumerNames_andEventTypes_wired() {
        // 两个消费者独立游标（consumer_offsets 按 name 一行）
        assertEquals("bureau-service:dpd-report", dpdConsumer.name());
        assertEquals("bureau-service:settled-report", settledConsumer.name());
        assertEquals(java.util.Set.of("DPD_CHANGED"), dpdConsumer.eventTypes());
        assertEquals(java.util.Set.of("LOAN_SETTLED"), settledConsumer.eventTypes());
    }
}
