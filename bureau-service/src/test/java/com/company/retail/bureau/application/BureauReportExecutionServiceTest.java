package com.company.retail.bureau.application;

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

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报送执行核心单测（W9-A）：触发→报送状态流转、消费幂等（重复事件不重复报送）、
 * 结清报送、INV-5 拦截 BLOCKED、CREDIT_BUREAU_REPORT_SENT payload 禁 PII（INV-4）。
 */
class BureauReportExecutionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);  // 周二（营业日）

    private InMemoryReportRepository reportRepository;
    private InMemoryBureauTriggerRepository triggerPort;
    private InMemoryConsentAnchor consentAnchor;
    private InMemoryMessageLogRepository messageLog;
    private RecordingOutboxPublisher outbox;
    private StubConsentGateClient consentClient;
    private BureauReportExecutionService executionService;

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
    }

    private BureauTriggerPort.TriggerRow registerDelinquencyTrigger(long loanId, long consentId) {
        triggerPort.insert(loanId, "M1", "DELINQUENCY", consentId, TODAY);
        return triggerPort.findByLoanAndType(loanId, "DELINQUENCY").orElseThrow();
    }

    // ---- 触发 → 报送执行状态流转 ----

    @Test
    void executeTrigger_sendsReport_consumesTrigger_publishesEvent() {
        var trigger = registerDelinquencyTrigger(101L, 5001L);

        var result = executionService.executeTrigger(trigger, "t1");

        assertEquals(BureauReportExecutionService.OUTCOME_SENT, result.outcome());
        // 状态流转：credit_bureau_reports PENDING → SENT（uk_report_loan_trigger 同 loan+trigger）
        ReportRow row = reportRepository.findByLoanAndTrigger(101L, "DELINQUENCY").orElseThrow();
        assertEquals("SENT", row.status());
        assertEquals("AUTO", row.source());
        // 触发推进：REGISTERED → CONSUMED
        assertEquals("CONSUMED", triggerPort.store.get(trigger.triggerId()).status());
        // 事件：CREDIT_BUREAU_REPORT_SENT，聚合键 reportId
        assertEquals(1, outbox.published.size());
        var event = outbox.published.get(0);
        assertEquals("CREDIT_BUREAU_REPORT_SENT", event.eventType());
        assertEquals(String.valueOf(row.reportId()), event.aggregateKey());
        // 报文留痕（出站 + 入站）
        assertTrue(messageLog.countByBizRef(String.valueOf(row.reportId())) >= 2);
    }

    @Test
    void executeTrigger_payloadOnlyBizKeys_noPii() {
        // INV-4：payload 过白名单守卫（RecordingOutboxPublisher 已强制），业务主键 reportId + 状态类键
        var trigger = registerDelinquencyTrigger(102L, 5001L);
        executionService.executeTrigger(trigger, "t2");

        var payload = outbox.published.get(0).payload();
        assertTrue(payload.contains("\"reportId\""));
        assertTrue(payload.contains("\"traceId\""));
        // 禁 PII 键（白名单外键会被 EventPayloadGuard 拒绝——这里再断言一遍语义）
        for (String banned : new String[]{"customerName", "name", "address", "phoneNumber",
                "birthday", "customerId"}) {
            assertTrue(!payload.contains("\"" + banned + "\""), "禁止键出现: " + banned);
        }
    }

    // ---- 消费幂等：重复事件不重复报送 ----

    @Test
    void executeTrigger_duplicate_alreadySent_skipsWithoutSecondReportOrEvent() {
        var trigger = registerDelinquencyTrigger(103L, 5001L);
        var first = executionService.executeTrigger(trigger, "t3");
        assertEquals(BureauReportExecutionService.OUTCOME_SENT, first.outcome());

        var second = executionService.executeTrigger(trigger, "t3");

        assertEquals(BureauReportExecutionService.OUTCOME_SKIPPED, second.outcome());
        assertEquals(first.reportId(), second.reportId());
        // 幂等：仍只有一条留痕、一个事件
        assertEquals(1, outbox.published.size());
        assertEquals(1, reportRepository.store.values().stream()
                .filter(r -> r.loanId() == 103L && "DELINQUENCY".equals(r.trigger())).count());
    }

    @Test
    void executeTrigger_consentWithdrawn_triggerBlocked_noReport() {
        // INV-5 / RB-1：同意撤回 → 触发 BLOCKED，正常返回（不进框架重试），不写报送留痕
        consentClient.setMode(5002L, StubConsentGateClient.Mode.DENY);
        var trigger = registerDelinquencyTrigger(104L, 5002L);

        var result = executionService.executeTrigger(trigger, "t4");

        assertEquals(BureauReportExecutionService.OUTCOME_BLOCKED, result.outcome());
        assertEquals("BLOCKED", triggerPort.store.get(trigger.triggerId()).status());
        assertTrue(reportRepository.findByLoanAndTrigger(104L, "DELINQUENCY").isEmpty());
        assertEquals(0, outbox.published.size());
    }

    // ---- 结清报送（LOAN_SETTLED → trigger=SETTLED，同一执行通道）----

    @Test
    void executeSettlement_resolvesConsentAnchor_sendsSettledReport() {
        consentAnchor.put(201L, 5001L);

        var result = executionService.executeSettlement(201L, "t5");

        assertEquals(BureauReportExecutionService.OUTCOME_SENT, result.outcome());
        ReportRow row = reportRepository.findByLoanAndTrigger(201L, "SETTLED").orElseThrow();
        assertEquals("SENT", row.status());
        assertEquals(1, outbox.published.size());
        assertEquals("CREDIT_BUREAU_REPORT_SENT", outbox.published.get(0).eventType());
        assertEquals(String.valueOf(row.reportId()), outbox.published.get(0).aggregateKey());
        assertTrue(messageLog.countByBizRef(String.valueOf(row.reportId())) >= 2);
    }

    @Test
    void executeSettlement_duplicate_alreadySent_skips() {
        consentAnchor.put(202L, 5001L);
        executionService.executeSettlement(202L, "t6");

        var second = executionService.executeSettlement(202L, "t6");

        assertEquals(BureauReportExecutionService.OUTCOME_SKIPPED, second.outcome());
        assertEquals(1, outbox.published.size());
    }

    @Test
    void executeSettlement_missingAnchor_throwsValid001() {
        // 锚点链缺失（无合同/无审查案件）→ RF_VALID_001（框架重试后 DEAD，留障不静默）
        var e = assertThrows(com.company.platform.exception.BusinessException.class,
                () -> executionService.executeSettlement(299L, "t7"));
        assertEquals("RF_VALID_001", e.getErrorCode().getCode());
        assertEquals("SETTLEMENT_CONSENT_ANCHOR_NOT_FOUND",
                String.valueOf(e.getDetails().get("reason")));
    }

    @Test
    void executeSettlement_consentWithdrawn_rejectedByGate() {
        consentAnchor.put(203L, 5002L);
        consentClient.setMode(5002L, StubConsentGateClient.Mode.DENY);

        var e = assertThrows(com.company.platform.exception.BusinessException.class,
                () -> executionService.executeSettlement(203L, "t8"));
        assertEquals("RF_UW_003", e.getErrorCode().getCode());
        assertTrue(reportRepository.findByLoanAndTrigger(203L, "SETTLED").isEmpty());
    }

    // ---- 策略点关闭 → 触发消费转人工语义（PENDING 不算失败）----

    @Test
    void executeTrigger_policyDisabled_pendingOutcome_noEvent() {
        var guard = new ConsentGateGuard(new ConsentGateFacade(consentClient, ""));
        var disabled = new DefaultCreditBureauReportingPolicy(false, "CIC,JICC", "CIC", 30, 1);
        var manualExecution = new BureauReportExecutionService(
                new BureauReportAppService(reportRepository, messageLog,
                        Map.of(CreditBureau.CIC, new com.company.retail.bureau.acl.CicStubAdapter(),
                                CreditBureau.JICC, new com.company.retail.bureau.acl.JiccStubAdapter()),
                        disabled, guard, outbox),
                reportRepository, triggerPort, consentAnchor);
        var trigger = registerDelinquencyTrigger(105L, 5001L);

        var result = manualExecution.executeTrigger(trigger, "t9");

        assertEquals(BureauReportExecutionService.OUTCOME_PENDING, result.outcome());
        assertEquals("PENDING", reportRepository.findByLoanAndTrigger(105L, "DELINQUENCY")
                .orElseThrow().status());
        assertEquals(0, outbox.published.size());
        assertNotNull(result);
    }
}
