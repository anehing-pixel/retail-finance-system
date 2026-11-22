package com.company.retail.bureau.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.bureau.application.TestFixtures.InMemoryDisputeRepository;
import com.company.retail.bureau.application.TestFixtures.InMemoryInquiryRepository;
import com.company.retail.bureau.application.TestFixtures.InMemoryMessageLogRepository;
import com.company.retail.bureau.application.TestFixtures.InMemoryReportRepository;
import com.company.retail.bureau.application.TestFixtures.RecordingOutboxPublisher;
import com.company.retail.bureau.domain.CreditBureau;
import com.company.retail.bureau.domain.DefaultCreditBureauReportingPolicy;
import com.company.retail.bureau.domain.InquiryStatus;
import com.company.retail.common.contract.ConsentGateFacade;
import com.company.retail.common.contract.StubConsentGateClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 征信防腐层用例级单测：INV-5/RB-1 fail-closed 门禁、重试上限转人工、
 * 报文留痕、事件 payload 白名单（禁 PII）、SLA 30 日。
 */
class BureauAppServicesTest {

    private InMemoryInquiryRepository inquiryRepository;
    private InMemoryReportRepository reportRepository;
    private InMemoryDisputeRepository disputeRepository;
    private InMemoryMessageLogRepository messageLog;
    private RecordingOutboxPublisher outbox;
    private StubConsentGateClient consentClient;
    private DefaultCreditBureauReportingPolicy policy;
    private CreditInquiryAppService inquiryService;
    private BureauReportAppService reportService;
    private BureauDisputeAppService disputeService;

    @BeforeEach
    void setUp() {
        inquiryRepository = new InMemoryInquiryRepository();
        reportRepository = new InMemoryReportRepository();
        disputeRepository = new InMemoryDisputeRepository();
        messageLog = new InMemoryMessageLogRepository();
        outbox = new RecordingOutboxPublisher();
        consentClient = new StubConsentGateClient(StubConsentGateClient.Mode.ALLOW);
        ConsentGateGuard guard = new ConsentGateGuard(new ConsentGateFacade(consentClient, ""));
        policy = new DefaultCreditBureauReportingPolicy(true, "CIC,JICC", "CIC", 30, 1);
        Map<CreditBureau, com.company.retail.bureau.acl.BureauAdapter> adapters = Map.of(
                CreditBureau.CIC, new com.company.retail.bureau.acl.CicStubAdapter(),
                CreditBureau.JICC, new com.company.retail.bureau.acl.JiccStubAdapter());
        inquiryService = new CreditInquiryAppService(inquiryRepository, messageLog, adapters,
                policy, guard, outbox);
        reportService = new BureauReportAppService(reportRepository, messageLog, adapters,
                policy, guard, outbox);
        disputeService = new BureauDisputeAppService(disputeRepository, messageLog, adapters, policy);
    }

    // ---- 查询（G4 / INV-5 / X-2）----

    @Test
    void inquiry_consentValid_completesWithTraceAndEvent() {
        var row = inquiryService.execute(1L, 5001L, "CIC", "UNDERWRITING", "t1");
        assertEquals("COMPLETED", row.status());
        // 报文留痕：出站 + 入站各一条
        assertTrue(messageLog.countByBizRef(String.valueOf(row.inquiryId())) >= 2);
        // 查询完成事件（聚合键 inquiryId）
        assertEquals(1, outbox.published.size());
        assertEquals("CREDIT_INQUIRY_COMPLETED", outbox.published.get(0).eventType());
        assertEquals(String.valueOf(row.inquiryId()), outbox.published.get(0).aggregateKey());
    }

    @Test
    void inquiry_consentWithdrawn_hardBlocked_noRowWritten() {
        // INV-5 / RB-1：同意已撤回 → 不得发起查询（不可配置旁路）
        consentClient.setMode(5002L, StubConsentGateClient.Mode.DENY);
        BusinessException e = assertThrows(BusinessException.class,
                () -> inquiryService.execute(1L, 5002L, "CIC", "UNDERWRITING", "t1"));
        assertEquals("RF_UW_003", e.getErrorCode().getCode());
        assertEquals(0, outbox.published.size());
    }

    @Test
    void inquiry_gateUnavailable_hardBlocked_failClosed() {
        // 查不到/超时 = 拒绝（fail-closed，宁可误拒不可放过）
        consentClient.setMode(5003L, StubConsentGateClient.Mode.UNAVAILABLE);
        BusinessException e = assertThrows(BusinessException.class,
                () -> inquiryService.execute(1L, 5003L, "CIC", "UNDERWRITING", "t1"));
        assertEquals("RF_UW_003", e.getErrorCode().getCode());
    }

    @Test
    void inquiry_bureauNotInCombination_rejected() {
        // C-05：查询双机构 CIC+JICC；ZENGIN 不在组合
        BusinessException e = assertThrows(BusinessException.class,
                () -> inquiryService.execute(1L, 5001L, "ZENGIN", "UNDERWRITING", "t1"));
        assertEquals("RF_VALID_001", e.getErrorCode().getCode());
    }

    @Test
    void inquiry_adapterFailure_marksFailed_keepsTrace_noEvent() {
        // 不写空结果、不留假 COMPLETED；失败留痕
        Map<CreditBureau, com.company.retail.bureau.acl.BureauAdapter> brokenAdapters = Map.of(
                CreditBureau.CIC, new com.company.retail.bureau.acl.BureauAdapter() {
                    @Override public CreditBureau bureau() { return CreditBureau.CIC; }
                    @Override public com.company.retail.bureau.acl.InquiryOutcome inquire(
                            com.company.retail.bureau.acl.BureauInquiryCommand c) {
                        throw new RuntimeException("line down");
                    }
                    @Override public com.company.retail.bureau.acl.ReportOutcome submitReport(
                            com.company.retail.bureau.acl.BureauReportCommand c) {
                        throw new UnsupportedOperationException();
                    }
                    @Override public com.company.retail.bureau.acl.DisputeOutcome fileDispute(
                            com.company.retail.bureau.acl.BureauDisputeCommand c) {
                        throw new UnsupportedOperationException();
                    }
                });
        CreditInquiryAppService broken = new CreditInquiryAppService(inquiryRepository, messageLog,
                brokenAdapters, policy, new ConsentGateGuard(new ConsentGateFacade(consentClient, "")), outbox);
        BusinessException e = assertThrows(BusinessException.class,
                () -> broken.execute(1L, 5001L, "CIC", "UNDERWRITING", "t1"));
        assertEquals("RF_UW_001", e.getErrorCode().getCode());
        assertEquals(InquiryStatus.FAILED.name(), inquiryRepository.findById(9001L).orElseThrow().status());
        assertEquals(0, outbox.published.size());
    }

    @Test
    void inquiry_threeRetriesThenManual_isEnforced() {
        // 预置同一案件同一机构 3 次 FAILED 留痕（X-2：3 次失败）
        for (int i = 0; i < 3; i++) {
            long id = inquiryRepository.insert(1L, 5001L, "CIC", "UNDERWRITING", "t1");
            inquiryRepository.markFinished(id, InquiryStatus.FAILED, null, 0, "t1");
        }
        // 第 4 次：重试上限 → 转人工（RF_UW_001，不自动无限重试）
        BusinessException e = assertThrows(BusinessException.class,
                () -> inquiryService.execute(1L, 5001L, "CIC", "UNDERWRITING", "t1"));
        assertEquals("RF_UW_001", e.getErrorCode().getCode());
    }

    // ---- 报送（C-05 / X-11 / INV-4）----

    @Test
    void report_consentValid_sendsAndPublishesEvent() {
        var row = reportService.sendReport(10L, 5001L, "SETTLED", "t2");
        assertEquals("SENT", row.status());
        assertEquals("CIC", row.bureau()); // C-05：报送主 CIC
        assertEquals(1, outbox.published.size());
        assertEquals("CREDIT_BUREAU_REPORT_SENT", outbox.published.get(0).eventType());
        assertEquals(String.valueOf(row.reportId()), outbox.published.get(0).aggregateKey());
        assertTrue(messageLog.countByBizRef(String.valueOf(row.reportId())) >= 2);
    }

    @Test
    void report_duplicateTrigger_conflict() {
        reportService.sendReport(10L, 5001L, "SETTLED", "t2");
        BusinessException e = assertThrows(BusinessException.class,
                () -> reportService.sendReport(10L, 5001L, "SETTLED", "t2"));
        assertEquals("RF_CONFLICT_001", e.getErrorCode().getCode());
    }

    @Test
    void report_consentWithdrawn_hardBlocked_noRow() {
        consentClient.setMode(5002L, StubConsentGateClient.Mode.DENY);
        BusinessException e = assertThrows(BusinessException.class,
                () -> reportService.sendReport(10L, 5002L, "SETTLED", "t2"));
        assertEquals("RF_UW_003", e.getErrorCode().getCode());
        assertEquals(0, outbox.published.size());
    }

    @Test
    void report_policyDisabled_goesManualChannel_pendingNoEvent() {
        // 策略点关闭 → 人工报送通道（留痕不丢弃、不发事件、不静默放行）
        DefaultCreditBureauReportingPolicy disabled =
                new DefaultCreditBureauReportingPolicy(false, "CIC,JICC", "CIC", 30, 1);
        BureauReportAppService manual = new BureauReportAppService(reportRepository, messageLog,
                Map.of(CreditBureau.CIC, new com.company.retail.bureau.acl.CicStubAdapter(),
                        CreditBureau.JICC, new com.company.retail.bureau.acl.JiccStubAdapter()),
                disabled, new ConsentGateGuard(new ConsentGateFacade(consentClient, "")), outbox);
        var row = manual.sendReport(20L, 5001L, "SETTLED", "t3");
        assertEquals("PENDING", row.status());
        assertEquals(0, outbox.published.size());
        // 人工重触发：策略点恢复后可走 resend
        var row2 = reportService.resend(row.reportId(), "t3");
        assertEquals("SENT", row2.status());
        assertEquals(1, outbox.published.size());
    }

    // ---- 异议（C-05 SLA 30 日）----

    @Test
    void dispute_open_setsSlaDueAt30Days() {
        var view = disputeService.open("CUST-REF-1", "CIC", "DISCLOSURE", "t4");
        assertEquals("OPEN", view.status());
        OffsetDateTime due = OffsetDateTime.parse(view.dueAt());
        assertTrue(due.isAfter(OffsetDateTime.now().plusDays(29)));
        assertTrue(due.isBefore(OffsetDateTime.now().plusDays(31)));
    }

    @Test
    void dispute_handle_resolvesWithCorrectionTrace() {
        var view = disputeService.open("CUST-REF-1", "CIC", "DISCLOSURE", "t4");
        var handled = disputeService.handle(view.disputeId(), "RES-REF-1", "t4");
        assertEquals("RESOLVED", handled.status());
        assertTrue(messageLog.countByBizRef(String.valueOf(view.disputeId())) >= 2);
    }

    @Test
    void dispute_overdue_isRejectedWithRfBureau002() {
        // C-05 SLA 超期 → 合规升级
        disputeRepository.insert("CUST-REF-9", "CIC", "DISCLOSURE",
                OffsetDateTime.now().minusDays(1), "t5");
        BusinessException e = assertThrows(BusinessException.class,
                () -> disputeService.handle(7001L, "RES-REF", "t5"));
        assertEquals("RF_BUREAU_002", e.getErrorCode().getCode());
    }

    @Test
    void dispute_doubleResolve_rejected() {
        var view = disputeService.open("CUST-REF-1", "CIC", "DISCLOSURE", "t4");
        disputeService.handle(view.disputeId(), "RES-REF-1", "t4");
        BusinessException e = assertThrows(BusinessException.class,
                () -> disputeService.handle(view.disputeId(), "RES-REF-2", "t4"));
        assertEquals("RF_VALID_002", e.getErrorCode().getCode());
    }
}
