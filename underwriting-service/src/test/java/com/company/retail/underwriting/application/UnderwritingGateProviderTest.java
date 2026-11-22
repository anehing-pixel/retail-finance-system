package com.company.retail.underwriting.application;

import com.company.retail.underwriting.application.TestFixtures.InMemoryCaseRepository;
import com.company.retail.underwriting.domain.DecisionOutcome;
import com.company.retail.underwriting.domain.UnderwritingCase;
import com.company.retail.underwriting.domain.UnderwritingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代付前审查门禁 provider 单测（R09 §7.1：retail-core 第三问；fail-closed）。
 */
class UnderwritingGateProviderTest {

    private InMemoryCaseRepository caseRepository;
    private UnderwritingGateProvider provider;

    @BeforeEach
    void setUp() {
        caseRepository = new InMemoryCaseRepository();
        provider = new UnderwritingGateProvider(caseRepository);
    }

    private UnderwritingCase insertFresh() {
        UnderwritingCase c = UnderwritingCase.start(1001L, 5001L, "W4-DEFAULT");
        caseRepository.insert(c);
        return c;
    }

    private UnderwritingCase insertReferred() {
        UnderwritingCase c = insertFresh();
        c.startInquiry();
        c.completeInquiry();
        c.beginAssessment();
        c.decide("userA", DecisionOutcome.REFERRED, null);
        caseRepository.update(c);
        return c;
    }

    private UnderwritingCase insertApproved() {
        UnderwritingCase c = insertReferred();
        c.overrideDecision("userB", DecisionOutcome.APPROVED);
        caseRepository.update(c);
        return c;
    }

    @Test
    void approvedCase_gateAllowed() {
        long caseId = insertApproved().getCaseId();
        Optional<UnderwritingGateProvider.GateView> view = provider.check(caseId);
        assertTrue(view.orElseThrow().allowed());
        assertEquals("APPROVED", view.orElseThrow().reasonCode());
    }

    @Test
    void nonApprovedStates_gateDenied() {
        assertFalse(provider.check(insertFresh().getCaseId()).orElseThrow().allowed());
        assertFalse(provider.check(insertReferred().getCaseId()).orElseThrow().allowed());

        UnderwritingCase declined = insertReferred();
        declined.overrideDecision("userB", DecisionOutcome.DECLINED);
        caseRepository.update(declined);
        assertFalse(provider.check(declined.getCaseId()).orElseThrow().allowed());
    }

    @Test
    void missingCase_deniedByControllerFallback() {
        // controller 侧 empty → GateView(false, CASE_NOT_FOUND)：不存在 ≠ 通过
        assertTrue(provider.check(99999L).isEmpty());
        assertFalse(new UnderwritingGateProvider.GateView(false, "CASE_NOT_FOUND").allowed());
    }

    @Test
    void expiredCase_gateDenied() {
        long caseId = insertApproved().getCaseId();
        UnderwritingCase c = caseRepository.findById(caseId).orElseThrow();
        c.expire();
        caseRepository.update(c);
        assertFalse(provider.check(caseId).orElseThrow().allowed());
        assertEquals(UnderwritingStatus.EXPIRED, c.getStatus());
    }
}
