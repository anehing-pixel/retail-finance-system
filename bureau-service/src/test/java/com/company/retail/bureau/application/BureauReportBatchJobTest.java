package com.company.retail.bureau.application;

import com.company.platform.calendar.BusinessCalendar;
import com.company.retail.bureau.application.TestFixtures.InMemoryBureauTriggerRepository;
import com.company.retail.bureau.application.TestFixtures.InMemoryConsentAnchor;
import com.company.retail.bureau.application.TestFixtures.InMemoryMessageLogRepository;
import com.company.retail.bureau.application.TestFixtures.InMemoryReportRepository;
import com.company.retail.bureau.application.TestFixtures.RecordingBatchRunPort;
import com.company.retail.bureau.application.TestFixtures.RecordingOutboxPublisher;
import com.company.retail.bureau.domain.CreditBureau;
import com.company.retail.bureau.domain.DefaultCreditBureauReportingPolicy;
import com.company.retail.common.contract.ConsentGateFacade;
import com.company.retail.common.contract.StubConsentGateClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报送日批单测（W9-A）：开关关=不跑、T+1 营业日判定（周末/节假日跳过）、
 * 到期触发逐条执行（复用消费者同一服务方法）、batch_runs 留痕。
 */
class BureauReportBatchJobTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 11, 24);   // 营业日
    private static final LocalDate SATURDAY = LocalDate.of(2026, 11, 28);  // 非营业日

    private InMemoryReportRepository reportRepository;
    private InMemoryBureauTriggerRepository triggerPort;
    private RecordingBatchRunPort batchRuns;
    private RecordingOutboxPublisher outbox;
    private BureauReportExecutionService executionService;
    private StubConsentGateClient consentClient;

    /** 营业日历挡板：周六周日非营业日 + 可注入节假日（core.holidays 语义）。 */
    private static final class StubBusinessCalendar implements BusinessCalendar {
        private final java.util.Set<LocalDate> holidays = new java.util.HashSet<>();

        void addHoliday(LocalDate d) {
            holidays.add(d);
        }

        @Override
        public boolean isBusinessDay(LocalDate date) {
            DayOfWeek dow = date.getDayOfWeek();
            return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(date);
        }

        @Override
        public LocalDate plusBusinessDays(LocalDate from, int n) {
            LocalDate cursor = from;
            int remaining = Math.abs(n);
            int step = n >= 0 ? 1 : -1;
            while (remaining > 0) {
                cursor = cursor.plusDays(step);
                if (isBusinessDay(cursor)) {
                    remaining--;
                }
            }
            return cursor;
        }
    }

    @BeforeEach
    void setUp() {
        reportRepository = new InMemoryReportRepository();
        triggerPort = new InMemoryBureauTriggerRepository();
        batchRuns = new RecordingBatchRunPort();
        outbox = new RecordingOutboxPublisher();
        consentClient = new StubConsentGateClient(StubConsentGateClient.Mode.ALLOW);
        var guard = new ConsentGateGuard(new ConsentGateFacade(consentClient, ""));
        var policy = new DefaultCreditBureauReportingPolicy(true, "CIC,JICC", "CIC", 30, 1);
        var adapters = Map.of(
                CreditBureau.CIC, new com.company.retail.bureau.acl.CicStubAdapter(),
                CreditBureau.JICC, new com.company.retail.bureau.acl.JiccStubAdapter());
        var reportAppService = new BureauReportAppService(reportRepository, new InMemoryMessageLogRepository(),
                adapters, policy, guard, outbox);
        executionService = new BureauReportExecutionService(reportAppService, reportRepository,
                triggerPort, new InMemoryConsentAnchor());
    }

    private BureauReportBatchJob newJob(boolean enabled, BusinessCalendar calendar) {
        return new BureauReportBatchJob(triggerPort, executionService, batchRuns, calendar, enabled);
    }

    // ---- 开关：默认关 = 不跑 ----

    @Test
    void batch_switchOff_doesNothing() {
        // 开关关（默认 false）：不执行触发、不写 batch_runs、不发自箱事件
        triggerPort.insert(501L, "M1", "DELINQUENCY", 5001L, TUESDAY.minusDays(1));
        var job = newJob(false, new StubBusinessCalendar());

        job.runBatch(TUESDAY);

        assertEquals(0, outbox.published.size());
        assertEquals(0, reportRepository.store.size());
        assertTrue(batchRuns.runs.isEmpty());
        assertEquals("REGISTERED", triggerPort.store.values().iterator().next().status());
    }

    // ---- T+1 营业日判定 ----

    @Test
    void batch_businessDay_executesDueTriggers() {
        // target_biz_date = T+1（周一 11-23）已到期 → 周二 11-24 执行
        triggerPort.insert(502L, "M1", "DELINQUENCY", 5001L, TUESDAY.minusDays(1));
        var job = newJob(true, new StubBusinessCalendar());

        job.runBatch(TUESDAY);

        assertEquals("SUCCESS", batchRuns.runs.get("BUREAU_REPORT|" + TUESDAY).status());
        assertEquals("SENT", reportRepository.findByLoanAndTrigger(502L, "DELINQUENCY")
                .orElseThrow().status());
        assertEquals(1, outbox.published.size());
    }

    @Test
    void batch_weekend_skippedWithTrace() {
        // 周六非营业日：SKIPPED 留痕，不执行任何报送
        triggerPort.insert(503L, "M1", "DELINQUENCY", 5001L, SATURDAY.minusDays(1));
        var job = newJob(true, new StubBusinessCalendar());

        job.runBatch(SATURDAY);

        assertEquals("SKIPPED", batchRuns.runs.get("BUREAU_REPORT|" + SATURDAY).status());
        assertEquals(0, outbox.published.size());
        assertEquals("REGISTERED", triggerPort.store.values().iterator().next().status());
    }

    @Test
    void batch_japaneseHoliday_skipped() {
        // core.holidays 节假日（文化の日 11-23 挡板化）→ SKIPPED
        StubBusinessCalendar calendar = new StubBusinessCalendar();
        LocalDate holiday = LocalDate.of(2026, 11, 23);
        assertTrue(calendar.isBusinessDay(holiday));          // 周一本非周末
        calendar.addHoliday(holiday);
        assertTrue(!calendar.isBusinessDay(holiday));          // 登记后为非营业日
        triggerPort.insert(504L, "M1", "DELINQUENCY", 5001L, holiday);
        var job = newJob(true, calendar);

        job.runBatch(holiday);

        assertEquals("SKIPPED", batchRuns.runs.get("BUREAU_REPORT|" + holiday).status());
        assertEquals(0, outbox.published.size());
    }

    @Test
    void batch_futureTargetBizDate_notExecutedYet() {
        // T+1 未到期（target_biz_date = 周三）→ 今日不执行
        triggerPort.insert(505L, "M1", "DELINQUENCY", 5001L, TUESDAY.plusDays(1));
        var job = newJob(true, new StubBusinessCalendar());

        job.runBatch(TUESDAY);

        assertEquals("SUCCESS", batchRuns.runs.get("BUREAU_REPORT|" + TUESDAY).status());
        assertEquals(0, outbox.published.size());
        assertEquals("REGISTERED", triggerPort.store.values().iterator().next().status());
    }

    @Test
    void batch_rerunSameDay_idempotent_noDuplicateReports() {
        // 同日重跑（uk_batch_job_date 复用 + uk_report_loan_trigger 幂等）：不重复报送
        triggerPort.insert(506L, "M1", "DELINQUENCY", 5001L, TUESDAY.minusDays(1));
        var job = newJob(true, new StubBusinessCalendar());

        job.runBatch(TUESDAY);
        job.runBatch(TUESDAY);

        assertEquals(1, outbox.published.size());
        assertEquals(1, reportRepository.store.values().stream()
                .filter(r -> r.loanId() == 506L).count());
        assertEquals("SUCCESS", batchRuns.runs.get("BUREAU_REPORT|" + TUESDAY).status());
    }

    @Test
    void batch_mixedDue_executesOnlyDueTriggers() {
        // 到期（昨日）执行、未到期（明日）保留 REGISTERED
        triggerPort.insert(507L, "M1", "DELINQUENCY", 5001L, TUESDAY.minusDays(1));
        triggerPort.insert(508L, "M2", "DELINQUENCY", 5001L, TUESDAY.plusDays(1));
        var job = newJob(true, new StubBusinessCalendar());

        job.runBatch(TUESDAY);

        assertEquals("SENT", reportRepository.findByLoanAndTrigger(507L, "DELINQUENCY")
                .orElseThrow().status());
        assertNull(reportRepository.findByLoanAndTrigger(508L, "DELINQUENCY").orElse(null));
        assertEquals("REGISTERED", triggerPort.findByLoanAndType(508L, "DELINQUENCY")
                .orElseThrow().status());
    }
}
