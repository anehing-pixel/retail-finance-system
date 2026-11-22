package com.company.retail.recon;

import com.company.retail.recon.application.ReconBatchRunRepository;
import com.company.retail.recon.application.ReconDiffRepository;
import com.company.retail.recon.application.ReconFinding;
import com.company.retail.recon.application.ReconQueryPort;
import com.company.retail.recon.application.ReconRunAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对拍批量执行用例：不一致检出、batch_runs 留痕（A28 幂等）、差异清单生命周期
 * （OPEN → RESOLVED，不物理删除）、INV-4 payload 白名单语义。
 */
class ReconRunAppServiceTest {

    private StubQueryPort queryPort;
    private InMemoryDiffRepository diffs;
    private InMemoryBatchRunRepository batchRuns;
    private ReconRunAppService appService;

    @BeforeEach
    void setUp() {
        queryPort = new StubQueryPort();
        diffs = new InMemoryDiffRepository();
        batchRuns = new InMemoryBatchRunRepository();
        appService = new ReconRunAppService(queryPort, diffs, batchRuns);
    }

    @Test
    void findings_becomeOpenDiffs_batchRunRecorded() {
        queryPort.findings.add(finding("INV-2", "LOAN", "1",
                Map.of("actualBalance", 1000L, "expectedBalance", 900L)));

        var summary = appService.run("2026-11-23", "t1");

        assertEquals(1, summary.findings());
        assertEquals(1, summary.openDiffs());
        assertEquals("SUCCESS", batchRuns.runs
                .get(new InMemoryBatchRunRepository.Key(ReconRunAppService.JOB_NAME, "2026-11-23")));
        assertEquals(1, diffs.list("INV-2", "OPEN").size());
        assertEquals(1000L, diffs.list("INV-2", "OPEN").get(0).detail().get("actualBalance"));
    }

    @Test
    void rerunSameDay_noDuplicateOpenDiff_batchRunReused() {
        // A28：同 (job, bizDate) 重跑 → batch_runs 复用同一行；同差异项不产生第二份
        queryPort.findings.add(finding("INV-2", "LOAN", "1", Map.of("actualBalance", 1L)));
        appService.run("2026-11-23", "t1");
        queryPort.findings.clear();
        queryPort.findings.add(finding("INV-2", "LOAN", "1", Map.of("actualBalance", 1L)));
        var second = appService.run("2026-11-23", "t1");

        assertEquals(1, second.findings());
        assertEquals(0, second.openDiffs());   // upsert 原位更新，非新增
        assertEquals(1, diffs.list(null, "OPEN").size());
        assertEquals(1, batchRuns.runs.size());
    }

    @Test
    void fixedInconsistency_resolved_notDeleted() {
        queryPort.findings.add(finding("INV-2", "LOAN", "1", Map.of("actualBalance", 1L)));
        appService.run("2026-11-23", "t1");
        // 修复后重跑：该差异不再检出 → RESOLVED（历史保留，不物理删除）
        queryPort.findings.clear();
        var second = appService.run("2026-11-23", "t1");
        assertEquals(1, second.resolvedDiffs());
        assertEquals(0, diffs.list(null, "OPEN").size());
        assertEquals(1, diffs.list(null, "RESOLVED").size());
    }

    @Test
    void allFiveInvCodes_supported() {
        for (String inv : new String[]{"INV-1", "INV-2", "INV-3", "INV-4", "INV-5"}) {
            queryPort.findings.add(finding(inv, "LOAN", "1", Map.of("reason", "x")));
        }
        var summary = appService.run("2026-11-23", "t1");
        assertEquals(5, summary.findings());
        assertEquals(5, diffs.list(null, "OPEN").size());
        assertEquals(5, diffs.list(null, null).size());
    }

    @Test
    void inv4_payloadWhitelist_semanticsViaGuard() {
        // INV-4 判定语义与发布端同源：复用 EventPayloadGuard——
        // 合法 payload（loanId + traceId）通过；含 PII 键 / 缺业务键的 payload 必须违规
        String valid = "{\"loanId\":1,\"stage\":\"M1\",\"traceId\":\"t1\",\"occurredAt\":\"x\","
                + "\"schemaVersion\":\"1.0\"}";
        String pii = "{\"loanId\":1,\"customerName\":\"太郎\",\"traceId\":\"t1\"}";
        String missingKey = "{\"stage\":\"M1\",\"traceId\":\"t1\"}";
        try {
            com.company.retail.common.contract.EventPayloadGuard.validate("DPD_CHANGED", valid);
            com.company.retail.common.contract.EventPayloadGuard.validate("DPD_CHANGED", pii);
            assertFalse(true, "PII payload 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("白名单"));
        }
        try {
            com.company.retail.common.contract.EventPayloadGuard.validate("DPD_CHANGED", missingKey);
            assertFalse(true, "缺业务主键的 payload 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            // requireBizKeyAndTraceId 拒绝
            assertTrue(true);
        }
        // 白名单外的键逐键拒绝（默认拒绝，不是默认放行）
        for (String banned : new String[]{"name", "address", "birthDate", "phone", "annualIncome",
                "accountNo", "myNumber", "encrypted_name"}) {
            String payload = "{\"loanId\":1,\"traceId\":\"t\",\"" + banned + "\":\"x\"}";
            try {
                com.company.retail.common.contract.EventPayloadGuard.validate("DPD_CHANGED", payload);
                assertFalse(true, "禁止键必须被拒绝：" + banned);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test
    void runFailure_batchRunMarkedFailed() {
        StubQueryPort throwing = new StubQueryPort() {
            @Override
            public List<ReconFinding> collectFindings() {
                throw new IllegalStateException("db down");
            }
        };
        ReconRunAppService failing = new ReconRunAppService(throwing, diffs, batchRuns);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> failing.run("2026-11-23", "t1"));
        assertEquals("FAILED", batchRuns.runs.get(
                new InMemoryBatchRunRepository.Key(ReconRunAppService.JOB_NAME, "2026-11-23")));
    }

    private static ReconFinding finding(String invCode, String entityType, String entityId,
                                        Map<String, Object> detail) {
        return new ReconFinding(invCode, entityType, entityId, detail);
    }

    // ---- 夹具 ----

    static class StubQueryPort implements ReconQueryPort {
        final List<ReconFinding> findings = new ArrayList<>();

        @Override
        public List<ReconFinding> collectFindings() {
            return new ArrayList<>(findings);
        }
    }

    static class InMemoryBatchRunRepository implements ReconBatchRunRepository {
        record Key(String jobName, String bizDate) {
        }

        final Map<Key, String> runs = new LinkedHashMap<>();

        @Override
        public void start(String jobName, String bizDate, String traceId) {
            runs.put(new Key(jobName, bizDate), "RUNNING");
        }

        @Override
        public void finish(String jobName, String bizDate, String status) {
            runs.put(new Key(jobName, bizDate), status);
        }

        @Override
        public List<RunRow> list(String jobNameOrNull) {
            return runs.entrySet().stream()
                    .map(e -> new RunRow(0, e.getKey().jobName(), e.getKey().bizDate(), e.getValue()))
                    .toList();
        }
    }

    static class InMemoryDiffRepository implements ReconDiffRepository {
        record Entry(long diffId, String jobName, String bizDate, String invCode,
                     String entityType, String entityId, Map<String, Object> detail, String status) {
        }

        final List<Entry> rows = new ArrayList<>();
        long seq = 0;

        @Override
        public boolean upsertOpen(String jobName, String bizDate, String invCode, String entityType,
                                  String entityId, Map<String, Object> detail, String traceId) {
            Optional<Entry> existing = rows.stream().filter(e -> "OPEN".equals(e.status())
                    && e.invCode().equals(invCode) && e.entityType().equals(entityType)
                    && e.entityId().equals(entityId)).findFirst();
            if (existing.isPresent()) {
                Entry old = existing.get();
                rows.remove(old);
                rows.add(new Entry(old.diffId(), jobName, bizDate, invCode, entityType, entityId,
                        detail, "OPEN"));
                return false;
            }
            rows.add(new Entry(++seq, jobName, bizDate, invCode, entityType, entityId, detail, "OPEN"));
            return true;
        }

        @Override
        public List<OpenKey> listOpenKeys() {
            return rows.stream().filter(e -> "OPEN".equals(e.status()))
                    .map(e -> new OpenKey(e.invCode(), e.entityType(), e.entityId()))
                    .toList();
        }

        @Override
        public boolean resolve(OpenKey key, String traceId) {
            Optional<Entry> existing = rows.stream().filter(e -> "OPEN".equals(e.status())
                    && e.invCode().equals(key.invCode()) && e.entityType().equals(key.entityType())
                    && e.entityId().equals(key.entityId())).findFirst();
            if (existing.isEmpty()) {
                return false;
            }
            Entry old = existing.get();
            rows.remove(old);
            rows.add(new Entry(old.diffId(), old.jobName(), old.bizDate(), old.invCode(),
                    old.entityType(), old.entityId(), old.detail(), "RESOLVED"));
            return true;
        }

        @Override
        public List<DiffRow> list(String invCodeOrNull, String statusOrNull) {
            return rows.stream()
                    .filter(e -> invCodeOrNull == null || e.invCode().equals(invCodeOrNull))
                    .filter(e -> statusOrNull == null || e.status().equals(statusOrNull))
                    .map(e -> new DiffRow(e.diffId(), e.jobName(), e.bizDate(), e.invCode(),
                            e.entityType(), e.entityId(), e.detail(), e.status()))
                    .toList();
        }

        @Override
        public Optional<DiffRow> findById(long diffId) {
            return rows.stream().filter(e -> e.diffId() == diffId).findFirst()
                    .map(e -> new DiffRow(e.diffId(), e.jobName(), e.bizDate(), e.invCode(),
                            e.entityType(), e.entityId(), e.detail(), e.status()));
        }
    }
}
