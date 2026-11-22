package com.company.retail.servicing;

import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.servicing.application.DpdBatchAppService;
import com.company.retail.servicing.application.LoanScheduleQueryPort;
import com.company.retail.servicing.domain.DpdCalculator;
import com.company.retail.servicing.domain.DpdStage;
import com.company.retail.servicing.ServicingTestFixtures.InMemoryBatchRunRepository;
import com.company.retail.servicing.ServicingTestFixtures.InMemoryCaseRepository;
import com.company.retail.servicing.ServicingTestFixtures.InMemorySnapshotRepository;
import com.company.retail.servicing.ServicingTestFixtures.RecordingAuditWriter;
import com.company.retail.servicing.ServicingTestFixtures.RecordingOutboxPublisher;
import com.company.retail.servicing.ServicingTestFixtures.StubLoanSchedulePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DPD 批处理用例：快照幂等（A28）、DPD_CHANGED 事件（payload 禁 PII，INV-4）、
 * 催收案件自动开立、batch_runs 留痕。
 */
class DpdBatchAppServiceTest {

    private static final LocalDate BIZ_DATE = LocalDate.parse("2026-11-23"); // Monday

    private StubLoanSchedulePort loans;
    private InMemorySnapshotRepository snapshots;
    private InMemoryCaseRepository cases;
    private InMemoryBatchRunRepository batchRuns;
    private RecordingAuditWriter audit;
    private RecordingOutboxPublisher outbox;
    private DpdBatchAppService appService;

    @BeforeEach
    void setUp() {
        loans = new StubLoanSchedulePort();
        snapshots = new InMemorySnapshotRepository();
        cases = new InMemoryCaseRepository();
        batchRuns = new InMemoryBatchRunRepository();
        audit = new RecordingAuditWriter();
        outbox = new RecordingOutboxPublisher();
        appService = new DpdBatchAppService(loans, snapshots, cases, batchRuns, audit,
                outbox, new DpdCalculator(0));
    }

    @Test
    void overdueItem_snapshotStageAndDpd_openCase() {
        loans.addLoan(1L, "ACTIVE");
        loans.addItem(10L, 1L, BIZ_DATE.minusDays(5), "OPEN");   // dpd=5 → M1

        var summary = appService.run(BIZ_DATE, "SYSTEM", "t1");

        assertEquals(1, summary.snapshotCount());
        assertEquals(1, summary.eventCount());
        assertEquals(1, summary.casesOpened());
        assertEquals(5, snapshots.find(1L, BIZ_DATE).orElseThrow().dpdDays());
        assertEquals(DpdStage.M1.name(), snapshots.find(1L, BIZ_DATE).orElseThrow().stage());
        assertEquals("M1", cases.findOpenByLoan(1L).orElseThrow().dpdStage());
        assertEquals("SUCCESS", batchRuns.runs.get(
                new InMemoryBatchRunRepository.Key(DpdBatchAppService.JOB_NAME, BIZ_DATE)));
    }

    @Test
    void rerunSameDay_noSecondSnapshotNoSecondEvent_noSecondCase() {
        // A28：同 (jobName, bizDate) 重跑不产生第二份数据
        loans.addLoan(1L, "ACTIVE");
        loans.addItem(10L, 1L, BIZ_DATE.minusDays(5), "OPEN");
        appService.run(BIZ_DATE, "SYSTEM", "t1");
        var second = appService.run(BIZ_DATE, "SYSTEM", "t1");

        assertEquals(0, second.snapshotCount());
        assertEquals(0, second.eventCount());
        assertEquals(0, second.casesOpened());
        assertEquals(1, snapshots.listByLoan(1L).size());
        assertEquals(1, outbox.published.size());
        assertEquals(1, cases.list(null).size());
    }

    @Test
    void naturalDayProgress_weekend_noSpuriousStageChange() {
        // 上一快照周五 M1(dpd=2)；周一自然日 dpd=5 仍 M1 → 不发事件（跨周末连续计数）
        loans.addLoan(1L, "ACTIVE");
        loans.addItem(10L, 1L, LocalDate.parse("2026-11-18"), "OPEN"); // Wednesday
        appService.run(LocalDate.parse("2026-11-20"), "SYSTEM", "t1"); // Friday, dpd=2
        appService.run(BIZ_DATE, "SYSTEM", "t1");                      // Monday, dpd=5
        assertEquals(5, snapshots.find(1L, BIZ_DATE).orElseThrow().dpdDays());
        assertEquals(1, outbox.published.size());
    }

    @Test
    void stageCrossing_m1ToM2_publishesChangedEvent() {
        loans.addLoan(1L, "ACTIVE");
        loans.addItem(10L, 1L, BIZ_DATE.minusDays(31), "OPEN");        // 10-23, dpd=31 → M2 当天
        appService.run(BIZ_DATE.minusDays(1), "SYSTEM", "t1");         // dpd=30 → M1
        appService.run(BIZ_DATE, "SYSTEM", "t1");                      // dpd=31 → M2
        assertEquals(2, outbox.published.size());
        assertEquals(DpdStage.M1.name(),
                snapshots.find(1L, BIZ_DATE.minusDays(1)).orElseThrow().stage());
        assertEquals(DpdStage.M2.name(),
                snapshots.find(1L, BIZ_DATE).orElseThrow().stage());
    }

    @Test
    void dpdChangedPayload_whitelistKeysOnly_noPii() {
        // INV-4：payload 只含白名单键；PII 键会被 RecordingOutboxPublisher 的守卫拒绝
        loans.addLoan(1L, "ACTIVE");
        loans.addItem(10L, 1L, BIZ_DATE.minusDays(5), "OPEN");
        appService.run(BIZ_DATE, "SYSTEM", "t1");

        var event = outbox.published.get(0);
        assertEquals("DPD_CHANGED", event.eventType());
        assertEquals("1", event.aggregateKey());
        var payload = parse(event.payload());
        for (String key : payload.keySet()) {
            assertTrue(EventPayloadGuard.ALLOWED_KEYS.contains(key), "payload 键不在白名单：" + key);
        }
        assertFalse(payload.containsKey("customerName"));
        assertFalse(payload.containsKey("address"));
        assertEquals("M1", payload.get("stage"));
        assertEquals(1L, ((Number) payload.get("loanId")).longValue());
    }

    @Test
    void recoveredLoan_stageCurrent_noCase_noEvent() {
        // 全部核销（W6 口径：status=PAID）→ dpd 0 / CURRENT，不开案件不发事件
        loans.addLoan(1L, "ACTIVE");
        loans.addItem(10L, 1L, BIZ_DATE.minusDays(5), "PAID");
        var summary = appService.run(BIZ_DATE, "SYSTEM", "t1");
        assertEquals(1, summary.snapshotCount());
        assertEquals(0, summary.eventCount());
        assertEquals(0, summary.casesOpened());
        assertEquals(DpdStage.CURRENT.name(),
                snapshots.find(1L, BIZ_DATE).orElseThrow().stage());
    }

    @Test
    void terminalLoan_skipped() {
        // 终态债权（B-7 四分 + CANCELLED）不再做快照
        loans.addLoan(1L, "SETTLED");
        loans.addLoan(2L, "WRITTEN_OFF");
        var summary = appService.run(BIZ_DATE, "SYSTEM", "t1");
        assertEquals(0, summary.loanCount());
        assertEquals(0, summary.snapshotCount());
    }

    @Test
    void preExistingOpenCase_notDuplicated() {
        loans.addLoan(1L, "ACTIVE");
        loans.addItem(10L, 1L, BIZ_DATE.minusDays(5), "OPEN");
        cases.insert(1L, "M2", "t0");
        var summary = appService.run(BIZ_DATE, "SYSTEM", "t1");
        assertEquals(0, summary.casesOpened());
        assertEquals("M2", cases.findOpenByLoan(1L).orElseThrow().dpdStage());
        assertEquals(1, cases.list(null).size());
    }

    @Test
    void batchRunFailure_markedFailed_noAudit() {
        // 数据源异常 → batch_runs 置 FAILED，不吞异常、不留成功审计
        StubLoanSchedulePort throwing = new StubLoanSchedulePort() {
            @Override
            public java.util.List<LoanScheduleQueryPort.LoanView> loadLoansForDpd() {
                throw new IllegalStateException("db down");
            }
        };
        DpdBatchAppService failing = new DpdBatchAppService(throwing, snapshots, cases,
                batchRuns, audit, outbox, new DpdCalculator(0));
        assertThrows(IllegalStateException.class, () -> failing.run(BIZ_DATE, "SYSTEM", "t1"));
        assertEquals("FAILED", batchRuns.runs.get(
                new InMemoryBatchRunRepository.Key(DpdBatchAppService.JOB_NAME, BIZ_DATE)));
        assertTrue(audit.entries.stream().noneMatch(e -> "DPD_BATCH_RUN".equals(e.action())));
    }

    @Test
    void auditRowWrittenWithActor() {
        loans.addLoan(1L, "ACTIVE");
        loans.addItem(10L, 1L, BIZ_DATE.minusDays(5), "OPEN");
        appService.run(BIZ_DATE, "SYSTEM", "t1");
        assertEquals(1, audit.entries.size());
        assertEquals("SYSTEM", audit.entries.get(0).actor());
        assertEquals("SYSTEM", audit.entries.get(0).actorSubject());
        assertEquals("DPD_BATCH_RUN", audit.entries.get(0).action());
    }

    private static Map<String, Object> parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
