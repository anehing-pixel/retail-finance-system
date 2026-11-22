package com.company.retail.underwriting.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.contract.ConsentGateFacade;
import com.company.retail.common.contract.StubConsentGateClient;
import com.company.retail.underwriting.application.TestFixtures.InMemoryAssessmentRepository;
import com.company.retail.underwriting.application.TestFixtures.InMemoryCaseRepository;
import com.company.retail.underwriting.application.TestFixtures.RecordingOutboxPublisher;
import com.company.retail.underwriting.domain.AssessmentResult;
import com.company.retail.underwriting.domain.AffordabilityInput;
import com.company.retail.underwriting.domain.DecisionOutcome;
import com.company.retail.underwriting.domain.StubAffordabilityPolicy;
import com.company.retail.underwriting.domain.UnderwritingCase;
import com.company.retail.underwriting.domain.UnderwritingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审查用例级单测：fail-closed 门禁语义（G3/X-3）、职责分离（G6/I-6）、
 * 事件发布（UNDERWRITING_APPROVED/DECLINED，payload 过白名单守卫）。
 */
class UnderwritingCaseAppServiceTest {

    private InMemoryCaseRepository caseRepository;
    private InMemoryAssessmentRepository assessmentRepository;
    private RecordingOutboxPublisher outbox;
    private StubConsentGateClient consentClient;
    private UnderwritingCaseAppService appService;

    @BeforeEach
    void setUp() {
        caseRepository = new InMemoryCaseRepository();
        assessmentRepository = new InMemoryAssessmentRepository();
        outbox = new RecordingOutboxPublisher();
        consentClient = new StubConsentGateClient(StubConsentGateClient.Mode.ALLOW);
        ConsentGateGuard guard = new ConsentGateGuard(new ConsentGateFacade(consentClient, ""));
        appService = new UnderwritingCaseAppService(caseRepository, assessmentRepository, guard,
                outbox, new StubAffordabilityPolicy(true));
    }

    @Test
    void createCase_consentAllowed_createsPendingCase() {
        UnderwritingCase c = appService.createCase(1001L, 5001L, "W4-DEFAULT", "trace-1");
        assertEquals(UnderwritingStatus.PENDING, c.getStatus());
        assertEquals(5001L, c.getConsentId());
    }

    @Test
    void createCase_consentWithdrawn_isHardBlocked_failClosed() {
        consentClient.setMode(5002L, StubConsentGateClient.Mode.DENY);
        BusinessException e = assertThrows(BusinessException.class,
                () -> appService.createCase(1001L, 5002L, "W4-DEFAULT", "trace-1"));
        assertEquals("RF_UW_003", e.getErrorCode().getCode());
        assertTrue(outbox.published.isEmpty());
    }

    @Test
    void createCase_gateUnavailable_isHardBlocked_failClosed() {
        // 门禁超时/不可用 ≠ 通过（R02 §7.1）：宁可误拒不可放过
        consentClient.setMode(5003L, StubConsentGateClient.Mode.UNAVAILABLE);
        BusinessException e = assertThrows(BusinessException.class,
                () -> appService.createCase(1001L, 5003L, "W4-DEFAULT", "trace-1"));
        assertEquals("RF_UW_003", e.getErrorCode().getCode());
    }

    @Test
    void createCase_gateNotOk_isHardBlocked_failClosed() {
        consentClient.setMode(5004L, StubConsentGateClient.Mode.NOT_OK);
        BusinessException e = assertThrows(BusinessException.class,
                () -> appService.createCase(1001L, 5004L, "W4-DEFAULT", "trace-1"));
        assertEquals("RF_UW_003", e.getErrorCode().getCode());
    }

    @Test
    void decide_consentWithdrawnBetweenReviewAndDecision_isBlocked() {
        // X-3：同意在审查中被撤回 → 立即中止决定（不缓存门禁结果，每次实查）
        UnderwritingCase c = appService.createCase(1001L, 5001L, "W4-DEFAULT", "t");
        c.startInquiry();
        c.completeInquiry();
        caseRepository.update(c);
        appService.assessAffordability(c.getCaseId(), new AffordabilityInput(
                "INSTALLMENT_CREDIT", "ENC-REF", 0L, 3_000_000L, 1_000_000L), "t");
        consentClient.setMode(5001L, StubConsentGateClient.Mode.DENY);
        BusinessException e = assertThrows(BusinessException.class,
                () -> appService.decide(c.getCaseId(), "userA", DecisionOutcome.APPROVED, null, "t"));
        assertEquals("RF_UW_003", e.getErrorCode().getCode());
    }

    @Test
    void decide_approved_publishesEvent_withWhitelistPayload() {
        long caseId = fullToAssessing();
        appService.decide(caseId, "userA", DecisionOutcome.APPROVED, null, "trace-9");
        assertEquals(1, outbox.published.size());
        var event = outbox.published.get(0);
        assertEquals("UNDERWRITING_APPROVED", event.eventType());
        assertEquals(String.valueOf(caseId), event.aggregateKey());
        assertTrue(event.payload().contains("\"caseId\":" + caseId));
        assertTrue(event.payload().contains("\"traceId\":\"trace-9\""));
    }

    @Test
    void decide_declined_publishesDeclinedEvent() {
        long caseId = fullToAssessing();
        appService.decide(caseId, "userA", DecisionOutcome.DECLINED, "OVER_DEBT", "t");
        assertEquals("UNDERWRITING_DECLINED", outbox.published.get(0).eventType());
    }

    @Test
    void decide_referred_thenOverride_sameActorRejected_differentActorApproved() {
        long caseId = fullToAssessing();
        appService.decide(caseId, "userA", DecisionOutcome.REFERRED, null, "t");
        outbox.published.clear();

        // SOD：复核人 = 决定人 → RF_AUTH_003 硬阻断
        BusinessException e = assertThrows(BusinessException.class,
                () -> appService.overrideDecision(caseId, "userA", DecisionOutcome.APPROVED, null, "t"));
        assertEquals("RF_AUTH_003", e.getErrorCode().getCode());

        appService.overrideDecision(caseId, "userB", DecisionOutcome.APPROVED, null, "t");
        assertEquals("UNDERWRITING_APPROVED", outbox.published.get(0).eventType());
    }

    @Test
    void assessAffordability_storesSnapshot_andMovesToAssessing() {
        long caseId = fullToInquiryDone();
        var output = appService.assessAffordability(caseId, new AffordabilityInput(
                "MONEY_LENDING", "ENC-REF-1", 100_000L, 3_000_000L, 2_000_000L), "t");
        assertEquals(AssessmentResult.PASS, output.result());
        assertEquals(UnderwritingStatus.ASSESSING, caseRepository.findById(caseId).orElseThrow().getStatus());
        // 重复评估 → RF_CONFLICT_001（append-only，不覆盖历史）
        BusinessException e = assertThrows(BusinessException.class,
                () -> appService.assessAffordability(caseId, new AffordabilityInput(
                        "MONEY_LENDING", "ENC-REF-1", 0L, 3_000_000L, 1_000_000L), "t"));
        assertEquals("RF_CONFLICT_001", e.getErrorCode().getCode());
    }

    private long fullToInquiryDone() {
        UnderwritingCase c = appService.createCase(1001L, 5001L, "W4-DEFAULT", "t");
        c.startInquiry();
        c.completeInquiry();
        caseRepository.update(c);
        return c.getCaseId();
    }

    private long fullToAssessing() {
        long caseId = fullToInquiryDone();
        appService.assessAffordability(caseId, new AffordabilityInput(
                "INSTALLMENT_CREDIT", "ENC-REF", 0L, 3_000_000L, 1_000_000L), "t");
        return caseId;
    }
}
