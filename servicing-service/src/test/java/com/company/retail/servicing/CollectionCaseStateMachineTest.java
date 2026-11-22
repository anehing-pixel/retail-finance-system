package com.company.retail.servicing;

import com.company.platform.exception.BusinessException;
import com.company.retail.servicing.application.CollectionActionRepository;
import com.company.retail.servicing.application.CollectionCaseAppService;
import com.company.retail.servicing.domain.CollectionActionType;
import com.company.retail.servicing.domain.CollectionCase;
import com.company.retail.servicing.domain.CollectionCaseStatus;
import com.company.retail.servicing.domain.CollectionTimePolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 催收案件状态机穷举 + 不可配置旁路（R08：催收必经案件，不允许绕过案件直接操作客户）。
 */
class CollectionCaseStateMachineTest {

    // ---- 聚合层：迁移穷举 ----

    @Test
    void legalPath_openToClosed_fullFlow() {
        CollectionCase c = newCase();
        c.assign("user-c1", "staff1", "t1");
        assertEquals(CollectionCaseStatus.ASSIGNED, c.status());
        c.contact("电话联系", "staff1", "t1");
        assertEquals(CollectionCaseStatus.IN_CONTACT, c.status());
        c.promise("承诺 12 月底前还款", "staff1", "t1");
        assertEquals(CollectionCaseStatus.PROMISED, c.status());
        c.close(CollectionCase.OUTCOME_REPAID, "已回收", "staff1", "t1");
        assertEquals(CollectionCaseStatus.CLOSED, c.status());
        assertTrue(c.isClosed());
    }

    @Test
    void promisedFallback_backToInContact_thenClose() {
        CollectionCase c = newCase();
        c.assign("user-c1", "staff1", "t1");
        c.contact("联系", "staff1", "t1");
        c.promise("承诺", "staff1", "t1");
        // 承诺未履约 → 回联系（结果流转分支）
        c.promiseBroken("承诺未履约", "staff1", "t1");
        assertEquals(CollectionCaseStatus.IN_CONTACT, c.status());
        c.close(CollectionCase.OUTCOME_UNRECOVERABLE, "无法回收", "staff1", "t1");
        assertEquals(CollectionCaseStatus.CLOSED, c.status());
    }

    @Test
    void illegalTransitions_exhaustivelyRejected() {
        // OPEN：只能 分案/关案
        CollectionCase open = newCase();
        assertThrows(BusinessException.class, () -> open.contact("x", "staff1", "t1"));
        assertThrows(BusinessException.class, () -> open.promise("x", "staff1", "t1"));
        assertThrows(BusinessException.class, () -> open.promiseBroken("x", "staff1", "t1"));

        // ASSIGNED：不能直接 PROMISE / 回 OPEN
        CollectionCase assigned = newCase();
        assigned.assign("user-c1", "staff1", "t1");
        assertThrows(BusinessException.class, () -> assigned.promise("x", "staff1", "t1"));
        assertThrows(BusinessException.class, () -> assigned.assign("user-c2", "staff1", "t1"));

        // IN_CONTACT：不能直接 ASSIGN
        CollectionCase contact = newCase();
        contact.assign("user-c1", "staff1", "t1");
        contact.contact("联系", "staff1", "t1");
        assertThrows(BusinessException.class, () -> contact.assign("user-c2", "staff1", "t1"));
        assertThrows(BusinessException.class, () -> contact.promiseBroken("x", "staff1", "t1"));

        // PROMISED：不能直接 PROMISE（重复承诺）
        CollectionCase promised = newCase();
        promised.assign("user-c1", "staff1", "t1");
        promised.contact("联系", "staff1", "t1");
        promised.promise("承诺", "staff1", "t1");
        assertThrows(BusinessException.class, () -> promised.promise("再承诺", "staff1", "t1"));
    }

    @Test
    void closed_isTerminal_noFurtherTransition() {
        CollectionCase c = newCase();
        c.assign("user-c1", "staff1", "t1");
        c.close(CollectionCase.OUTCOME_REPAID, "回收", "staff1", "t1");
        assertThrows(BusinessException.class, () -> c.assign("user-c2", "staff1", "t1"));
        assertThrows(BusinessException.class, () -> c.contact("x", "staff1", "t1"));
        assertThrows(BusinessException.class, () -> c.promise("x", "staff1", "t1"));
        assertThrows(BusinessException.class, () ->
                c.close(CollectionCase.OUTCOME_REPAID, "again", "staff1", "t1"));
        assertEquals(CollectionCaseStatus.CLOSED, c.status());
    }

    @Test
    void close_requiresExplicitOutcome() {
        CollectionCase c = newCase();
        c.assign("user-c1", "staff1", "t1");
        assertThrows(BusinessException.class, () -> c.close(null, "no outcome", "staff1", "t1"));
        assertThrows(BusinessException.class, () -> c.close("MAYBE", "unknown", "staff1", "t1"));
    }

    @Test
    void assign_requiresAssignee() {
        CollectionCase c = newCase();
        assertThrows(BusinessException.class, () -> c.assign(null, "staff1", "t1"));
        assertThrows(BusinessException.class, () -> c.assign(" ", "staff1", "t1"));
    }

    // ---- 应用层：不可配置旁路 + 时段约束 ----

    @Test
    void action_onMissingCase_hardRejected_noBypassSwitch() {
        // 催收必经案件：案件不存在即拒绝——没有任务开关、白名单或任何配置能让它放行
        var fixtures = harness(CollectionTimePolicy.defaults());
        BusinessException e = assertThrows(BusinessException.class,
                () -> fixtures.appService.executeAction(999999L, "CONTACT", null, null, "note",
                        "staff1", "STAFF", withinWindow(), "t1"));
        assertEquals("RF_VALID_002", e.getErrorCode().getCode());
        assertEquals("COLLECTION_CASE_NOT_FOUND", e.getDetails().get("reason"));
        // 即使时段窗口配置成极端宽（00:00–23:59），无案件依旧拒绝
        var wide = harness(new CollectionTimePolicy(LocalTime.MIN, LocalTime.of(23, 59)));
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> wide.appService.executeAction(999999L, "CONTACT", null, null, "note",
                        "staff1", "STAFF", withinWindow(), "t1"));
        assertEquals("RF_VALID_002", e2.getErrorCode().getCode());
    }

    @Test
    void action_outsideTimeWindow_rejected() {
        var fixtures = harness(CollectionTimePolicy.defaults());
        fixtures.caseRepo.insert(1L, "M1", "t0");
        var caseId = fixtures.caseRepo.findOpenByLoan(1L).orElseThrow().caseId();
        // 23:00 JST 窗口外（默认 09:00–20:00）
        OffsetDateTime night = OffsetDateTime.of(2026, 11, 23, 23, 0, 0, 0, ZoneOffset.ofHours(9));
        BusinessException e = assertThrows(BusinessException.class,
                () -> fixtures.appService.executeAction(caseId, "CONTACT", null, null, "note",
                        "staff1", "STAFF", night, "t1"));
        assertEquals("RF_VALID_002", e.getErrorCode().getCode());
        assertEquals("COLLECTION_TIME_WINDOW", e.getDetails().get("reason"));
        assertEquals(0, fixtures.actionRepo.listByCase(caseId).size());
    }

    @Test
    void action_fullFlow_appendsAuditAndActionRows() {
        var fixtures = harness(CollectionTimePolicy.defaults());
        assertTrue(fixtures.caseRepo.insert(1L, "M1", "t0"));
        long caseId = fixtures.caseRepo.findOpenByLoan(1L).orElseThrow().caseId();

        var r1 = fixtures.appService.executeAction(caseId, "ASSIGN", "user-c1", null, null,
                "staff1", "STAFF", withinWindow(), "t1");
        assertEquals("ASSIGNED", r1.caseStatus());
        var r2 = fixtures.appService.executeAction(caseId, "CONTACT", null, null, "联系",
                "staff1", "STAFF", withinWindow(), "t1");
        assertEquals("IN_CONTACT", r2.caseStatus());
        var r3 = fixtures.appService.executeAction(caseId, "PROMISE", null, null, "承诺",
                "staff1", "STAFF", withinWindow(), "t1");
        assertEquals("PROMISED", r3.caseStatus());
        var r4 = fixtures.appService.executeAction(caseId, "CLOSE", null,
                CollectionCase.OUTCOME_REPAID, "回收", "staff1", "STAFF", withinWindow(), "t1");
        assertEquals("CLOSED", r4.caseStatus());

        // 动作留痕 append-only + 审计逐动作落表
        assertEquals(4, fixtures.actionRepo.listByCase(caseId).size());
        assertEquals(4, fixtures.audit.entries.size());
        assertEquals("COLLECTION_ACTION", fixtures.audit.entries.get(0).action());
        assertEquals("staff1", fixtures.audit.entries.get(0).actor());
    }

    @Test
    void unknownActionType_rejected() {
        var fixtures = harness(CollectionTimePolicy.defaults());
        fixtures.caseRepo.insert(1L, "M1", "t0");
        long caseId = fixtures.caseRepo.findOpenByLoan(1L).orElseThrow().caseId();
        BusinessException e = assertThrows(BusinessException.class,
                () -> fixtures.appService.executeAction(caseId, "PHONE_CALL", null, null, null,
                        "staff1", "STAFF", withinWindow(), "t1"));
        assertEquals("RF_VALID_001", e.getErrorCode().getCode());
    }

    @Test
    void closedCase_furtherActionRejected() {
        var fixtures = harness(CollectionTimePolicy.defaults());
        fixtures.caseRepo.insert(1L, "M1", "t0");
        long caseId = fixtures.caseRepo.findOpenByLoan(1L).orElseThrow().caseId();
        fixtures.appService.executeAction(caseId, "ASSIGN", "user-c1", null, null,
                "staff1", "STAFF", withinWindow(), "t1");
        fixtures.appService.executeAction(caseId, "CLOSE", null,
                CollectionCase.OUTCOME_UNRECOVERABLE, "呆账", "staff1", "STAFF", withinWindow(), "t1");
        BusinessException e = assertThrows(BusinessException.class,
                () -> fixtures.appService.executeAction(caseId, "CONTACT", null, null, "x",
                        "staff1", "STAFF", withinWindow(), "t1"));
        assertEquals("RF_VALID_002", e.getErrorCode().getCode());
    }

    @Test
    void timePolicy_boundsAndZone() {
        CollectionTimePolicy policy = new CollectionTimePolicy(LocalTime.of(9, 0), LocalTime.of(20, 0));
        // 09:00 JST 含边界；19:59 JST 在窗口内；20:00 JST 不含
        assertTrue(policy.allows(OffsetDateTime.of(2026, 11, 23, 9, 0, 0, 0, ZoneOffset.ofHours(9))));
        assertTrue(policy.allows(OffsetDateTime.of(2026, 11, 23, 19, 59, 0, 0, ZoneOffset.ofHours(9))));
        var fixtures = harness(policy);
        // UTC 05:00 = JST 14:00 → 窗口内（时区归一 JST）
        assertTrue(policy.allows(OffsetDateTime.of(2026, 11, 23, 5, 0, 0, 0, ZoneOffset.UTC)));
        // UTC 11:01 = JST 20:01 → 窗口外
        var night = OffsetDateTime.of(2026, 11, 23, 11, 1, 0, 0, ZoneOffset.UTC);
        fixtures.caseRepo.insert(1L, "M1", "t0");
        long caseId = fixtures.caseRepo.findOpenByLoan(1L).orElseThrow().caseId();
        assertThrows(BusinessException.class,
                () -> fixtures.appService.executeAction(caseId, "CONTACT", null, null, "x",
                        "staff1", "STAFF", night, "t1"));
    }

    // ---- 夹具 ----

    private record Harness(CollectionCaseAppService appService,
                           ServicingTestFixtures.InMemoryCaseRepository caseRepo,
                           CollectionActionRepository actionRepo,
                           ServicingTestFixtures.RecordingAuditWriter audit) {
    }

    private static Harness harness(CollectionTimePolicy policy) {
        var caseRepo = new ServicingTestFixtures.InMemoryCaseRepository();
        var actionRepo = new ServicingTestFixtures.InMemoryActionRepository();
        var audit = new ServicingTestFixtures.RecordingAuditWriter();
        return new Harness(new CollectionCaseAppService(caseRepo, actionRepo, policy, audit),
                caseRepo, actionRepo, audit);
    }

    private static OffsetDateTime withinWindow() {
        // 2026-11-23 10:00 JST（窗口内）
        return OffsetDateTime.of(2026, 11, 23, 10, 0, 0, 0, ZoneOffset.ofHours(9));
    }

    private static CollectionCase newCase() {
        return new CollectionCase(1L, 100L, "M1", CollectionCaseStatus.OPEN, null, 0);
    }
}
