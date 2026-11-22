package com.company.retail.servicing;

import com.company.platform.exception.BusinessException;
import com.company.retail.servicing.application.BureauReportTriggerAppService;
import com.company.retail.servicing.ServicingTestFixtures.InMemoryBatchRunRepository;
import com.company.retail.servicing.ServicingTestFixtures.InMemoryTriggerRepository;
import com.company.retail.servicing.ServicingTestFixtures.RecordingAuditWriter;
import com.company.retail.servicing.ServicingTestFixtures.RecordingOutboxPublisher;
import com.company.retail.servicing.ServicingTestFixtures.StubBusinessCalendar;
import com.company.retail.servicing.ServicingTestFixtures.StubConsentGate;
import com.company.retail.servicing.ServicingTestFixtures.StubOutboxTrackingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报送触发用例：T+1 营业日口径（C-05）、INV-5 fail-closed 同意门禁、幂等、状态跟踪。
 */
class BureauReportTriggerAppServiceTest {

    private static final LocalDate FRIDAY = LocalDate.parse("2026-11-20");   // Friday

    private InMemoryTriggerRepository triggers;
    private StubConsentGate consentGate;
    private StubOutboxTrackingPort outboxTracking;
    private StubBusinessCalendar calendar;
    private RecordingAuditWriter audit;
    private RecordingOutboxPublisher outbox;
    private BureauReportTriggerAppService appService;

    @BeforeEach
    void setUp() {
        triggers = new InMemoryTriggerRepository();
        consentGate = new StubConsentGate();
        outboxTracking = new StubOutboxTrackingPort();
        calendar = new StubBusinessCalendar();
        audit = new RecordingAuditWriter();
        outbox = new RecordingOutboxPublisher();
        appService = new BureauReportTriggerAppService(triggers, consentGate, outboxTracking,
                calendar, audit, 1);
    }

    @Test
    void tPlusOne_businessDay_targetDate() {
        // T+1 营业日：target = 下一个营业日（周末/节假日自动跳过，C-05 裁决值）
        consentGate.grant(5001L);
        var row = appService.register(1L, 5001L, "M1", "staff1", "t1");
        LocalDate expected = calendar.plusBusinessDays(LocalDate.now(), 1);
        assertTrue(expected.isAfter(LocalDate.now()));
        assertEquals(expected, row.targetBizDate());
        assertEquals("REGISTERED", row.status());
        assertEquals("DELINQUENCY", row.triggerType());
        assertNotWeekend(row.targetBizDate());
    }

    @Test
    void tPlusOne_skipsWeekendAndHoliday() {
        // 纯日历口径：周五 +1 营业日 → 下周一（无假日时）；加周一假日 → 下周二
        assertEquals(LocalDate.parse("2026-11-23"), calendar.plusBusinessDays(FRIDAY, 1));
        calendar.addHoliday(LocalDate.parse("2026-11-23")); // 勤労感謝の日
        assertEquals(LocalDate.parse("2026-11-24"), calendar.plusBusinessDays(FRIDAY, 1));
    }

    @Test
    void consentWithdrawn_hardBlocked_failClosed() {
        // INV-5 / RB-1：同意撤回 → 不得登记报送触发（无白名单旁路）
        consentGate.grant(5002L);
        consentGate.revoke(5002L);
        BusinessException e = assertThrows(BusinessException.class,
                () -> appService.register(1L, 5002L, "M1", "staff1", "t1"));
        assertEquals("RF_UW_003", e.getErrorCode().getCode());
        assertEquals(0, triggers.list(null).size());
        assertEquals(0, audit.entries.size());
    }

    @Test
    void consentUnknown_hardBlocked_failClosed() {
        // 查不到 / 超时 = 拒绝（fail-closed，宁可误拒不可放过）
        BusinessException e = assertThrows(BusinessException.class,
                () -> appService.register(1L, 9999L, "M1", "staff1", "t1"));
        assertEquals("RF_UW_003", e.getErrorCode().getCode());
    }

    @Test
    void duplicateTrigger_conflict() {
        consentGate.grant(5001L);
        appService.register(1L, 5001L, "M1", "staff1", "t1");
        BusinessException e = assertThrows(BusinessException.class,
                () -> appService.register(1L, 5001L, "M2", "staff1", "t1"));
        assertEquals("RF_CONFLICT_001", e.getErrorCode().getCode());
        assertEquals(1, triggers.list(null).size());
    }

    @Test
    void invalidArgs_rejected() {
        assertThrows(BusinessException.class,
                () -> appService.register(0L, 5001L, "M1", "staff1", "t1"));
        assertThrows(BusinessException.class,
                () -> appService.register(1L, 0L, "M1", "staff1", "t1"));
    }

    @Test
    void track_exposesOutboxDeliveryAnchor() {
        // 状态跟踪：触发登记 + DPD_CHANGED outbox 送达锚点（不跨域读 bureau 真相源）
        consentGate.grant(5001L);
        var row = appService.register(1L, 5001L, "M1", "staff1", "t1");
        outboxTracking.put("DPD_CHANGED", "1", "SENT");
        var data = appService.track(row.triggerId(), "t1");
        assertEquals("SENT", data.get("outboxEventStatus"));
        assertEquals("REGISTERED", data.get("status"));
        assertEquals("DELINQUENCY", data.get("triggerType"));
    }

    @Test
    void auditRowWritten() {
        consentGate.grant(5001L);
        var row = appService.register(1L, 5001L, "M1", "staff1", "t1");
        assertEquals(1, audit.entries.size());
        assertEquals("REPORT_TRIGGER_REGISTERED", audit.entries.get(0).action());
        assertEquals("BUREAU_REPORT_TRIGGER", audit.entries.get(0).targetType());
        assertEquals(String.valueOf(row.triggerId()), audit.entries.get(0).targetId());
        Map<String, Object> snapshot = audit.entries.get(0).afterSnapshot();
        // 审计快照不落 PII（只含业务键）
        assertTrue(!snapshot.containsKey("customerName"));
    }

    @Test
    void zeroLeadDays_rejected() {
        // T+1 口径：leadDays < 1 直接拒绝构造
        assertThrows(IllegalArgumentException.class,
                () -> new BureauReportTriggerAppService(triggers, consentGate, outboxTracking,
                        calendar, audit, 0));
    }

    private static void assertNotWeekend(LocalDate target) {
        DayOfWeek dow = target.getDayOfWeek();
        assertTrue(dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY,
                "T+1 目标日不能落在周末");
    }
}
