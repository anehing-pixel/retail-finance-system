package com.company.retail.bureau.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.bureau.application.TestFixtures.InMemoryMessageLogRepository;
import com.company.retail.bureau.application.TestFixtures.InMemoryReportRepository;
import com.company.retail.bureau.domain.CreditBureau;
import com.company.retail.bureau.domain.DefaultCreditBureauReportingPolicy;
import com.company.retail.common.contract.ConsentGateFacade;
import com.company.retail.common.contract.StubConsentGateClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人工报送通道单测（ADR-RF-005 §5 / W9-A）：登记 → PENDING(source=MANUAL) →
 * 结果录入 → SENT/REJECTED，录入报文落 INBOUND 留痕；INV-5 fail-closed；
 * uk_report_loan_trigger 幂等。
 */
class ManualReportAppServiceTest {

    private InMemoryReportRepository reportRepository;
    private InMemoryMessageLogRepository messageLog;
    private StubConsentGateClient consentClient;
    private ManualReportAppService manualService;

    @BeforeEach
    void setUp() {
        reportRepository = new InMemoryReportRepository();
        messageLog = new InMemoryMessageLogRepository();
        consentClient = new StubConsentGateClient(StubConsentGateClient.Mode.ALLOW);
        var guard = new ConsentGateGuard(new ConsentGateFacade(consentClient, ""));
        var policy = new DefaultCreditBureauReportingPolicy(true, "CIC,JICC", "CIC", 30, 1);
        manualService = new ManualReportAppService(reportRepository, messageLog, policy, guard);
    }

    @Test
    void registerManualRequest_pendsWithManualSource_andLogsOutbound() {
        var row = manualService.registerManualRequest(601L, 5001L, null, "机构未审批，走人工通道", "t1");

        assertEquals("PENDING", row.status());
        assertEquals("MANUAL", row.source());
        assertEquals("MANUAL", row.trigger());
        assertEquals("CIC", row.bureau());   // 未指定机构 → 报送主 CIC
        assertEquals(1, messageLog.entries.size());
        assertEquals("OUTBOUND", messageLog.entries.get(0).direction());
        assertTrue(messageLog.entries.get(0).messageText().contains("\"manual\":true"));
    }

    @Test
    void registerManualRequest_duplicate_conflict() {
        manualService.registerManualRequest(602L, 5001L, null, null, "t2");
        BusinessException e = assertThrows(BusinessException.class,
                () -> manualService.registerManualRequest(602L, 5001L, null, null, "t2"));
        assertEquals("RF_CONFLICT_001", e.getErrorCode().getCode());
    }

    @Test
    void registerManualRequest_consentWithdrawn_hardBlocked() {
        // INV-5 / RB-1：人工通道同样 fail-closed
        consentClient.setMode(5002L, StubConsentGateClient.Mode.DENY);
        BusinessException e = assertThrows(BusinessException.class,
                () -> manualService.registerManualRequest(603L, 5002L, null, null, "t3"));
        assertEquals("RF_UW_003", e.getErrorCode().getCode());
        assertTrue(reportRepository.store.isEmpty());
    }

    @Test
    void recordManualResult_accepted_marksSent_logsInboundRawMessage() {
        var registered = manualService.registerManualRequest(604L, 5001L, "JICC", null, "t4");

        var recorded = manualService.recordManualResult(registered.reportId(),
                "JICC-RESP-20261124 受理代码 OK-12345", "RESP-REF-1", "ACCEPTED", "t4");

        assertEquals("SENT", recorded.status());
        assertEquals("MANUAL", recorded.source());
        var inbound = messageLog.entries.stream()
                .filter(e -> "INBOUND".equals(e.direction())).findFirst().orElseThrow();
        assertEquals(String.valueOf(registered.reportId()), inbound.bizRef());
        assertTrue(inbound.messageText().contains("受理代码 OK-12345"));   // 录入报文原文留痕
    }

    @Test
    void recordManualResult_rejected_keepsPendingForReEntry() {
        // X-11：被拒不静默丢弃 → REJECTED 留痕（可经人工通道继续处理）
        var registered = manualService.registerManualRequest(605L, 5001L, null, null, "t5");

        var recorded = manualService.recordManualResult(registered.reportId(),
                "機構拒收: 字段不足", null, "REJECTED", "t5");

        assertEquals("REJECTED", recorded.status());
    }

    @Test
    void recordManualResult_alreadyRecorded_rejected() {
        var registered = manualService.registerManualRequest(606L, 5001L, null, null, "t6");
        manualService.recordManualResult(registered.reportId(), "OK", null, "ACCEPTED", "t6");

        BusinessException e = assertThrows(BusinessException.class,
                () -> manualService.recordManualResult(registered.reportId(), "again", null, "ACCEPTED", "t6"));
        assertEquals("RF_VALID_002", e.getErrorCode().getCode());
    }

    @Test
    void recordManualResult_emptyRawMessage_rejected() {
        var registered = manualService.registerManualRequest(607L, 5001L, null, null, "t7");
        BusinessException e = assertThrows(BusinessException.class,
                () -> manualService.recordManualResult(registered.reportId(), " ", null, "ACCEPTED", "t7"));
        assertEquals("RF_VALID_001", e.getErrorCode().getCode());
    }

    @Test
    void recordManualResult_unknownReport_notFound() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> manualService.recordManualResult(99999L, "OK", null, "ACCEPTED", "t8"));
        assertEquals("RF_VALID_001", e.getErrorCode().getCode());
    }

    @Test
    void registerManualRequest_unknownBureau_rejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> manualService.registerManualRequest(608L, 5001L, "ZENGIN", null, "t9"));
        assertEquals("RF_VALID_001", e.getErrorCode().getCode());
    }
}
