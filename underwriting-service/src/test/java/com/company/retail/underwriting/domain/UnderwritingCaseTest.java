package com.company.retail.underwriting.domain;

import com.company.platform.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审查状态机纯单测（R04 §3.2 / §7 U-3；零 Spring，不连库）。
 */
class UnderwritingCaseTest {

    private UnderwritingCase newCase() {
        return UnderwritingCase.start(1001L, 5001L, "W4-DEFAULT");
    }

    private UnderwritingCase assessingCase() {
        UnderwritingCase c = newCase();
        c.startInquiry();
        c.completeInquiry();
        c.beginAssessment();
        return c;
    }

    @Test
    void happyPath_reachesApproved_withDecisionRecorded() {
        UnderwritingCase c = assessingCase();
        DecisionOutcome outcome = c.decide("userA", DecisionOutcome.APPROVED, "W4-DEFAULT");
        assertEquals(DecisionOutcome.APPROVED, outcome);
        assertEquals(UnderwritingStatus.APPROVED, c.getStatus());
        assertEquals("userA", c.getDecidedBy());
    }

    @Test
    void decideBeforeInquiryDone_isRejected() {
        UnderwritingCase c = newCase(); // 仍 PENDING，未查询
        c.startInquiry(); // INQUIRY_IN_PROGRESS
        BusinessException e = assertThrows(BusinessException.class,
                () -> c.decide("userA", DecisionOutcome.APPROVED, null));
        assertEquals("RF_VALID_002", e.getErrorCode().getCode());
    }

    @Test
    void cannotSkipInquiryDone() {
        UnderwritingCase c = newCase();
        c.startInquiry();
        BusinessException e = assertThrows(BusinessException.class, c::beginAssessment);
        assertEquals("RF_VALID_002", e.getErrorCode().getCode());
    }

    @Test
    void terminalState_cannotTransitionAgain() {
        UnderwritingCase c = assessingCase();
        c.decide("userA", DecisionOutcome.DECLINED, "OVER_DEBT");
        BusinessException e = assertThrows(BusinessException.class,
                () -> c.decide("userB", DecisionOutcome.APPROVED, null));
        assertEquals("RF_VALID_002", e.getErrorCode().getCode());
        assertTrue(c.getStatus().isTerminal());
    }

    @Test
    void inquiryFailure_canRetry() {
        UnderwritingCase c = newCase();
        c.startInquiry();
        c.failInquiry();
        assertEquals(UnderwritingStatus.INQUIRY_FAILED, c.getStatus());
        c.startInquiry(); // 重试入口
        assertEquals(UnderwritingStatus.INQUIRY_IN_PROGRESS, c.getStatus());
    }

    @Test
    void sod_selfOverride_isHardBlocked_withAuth003() {
        UnderwritingCase c = assessingCase();
        c.decide("userA", DecisionOutcome.REFERRED, null);
        // I-6：OVERRIDE 与 DECIDED 不可同一自然人（RF_AUTH_003，RB-7 不可白名单豁免）
        BusinessException e = assertThrows(BusinessException.class,
                () -> c.overrideDecision("userA", DecisionOutcome.APPROVED));
        assertEquals("RF_AUTH_003", e.getErrorCode().getCode());
        assertEquals(UnderwritingStatus.REFERRED, c.getStatus()); // 状态未变
    }

    @Test
    void sod_differentReviewer_canOverride() {
        UnderwritingCase c = assessingCase();
        c.decide("userA", DecisionOutcome.REFERRED, null);
        c.overrideDecision("userB", DecisionOutcome.APPROVED);
        assertEquals(UnderwritingStatus.APPROVED, c.getStatus());
        assertEquals("userB", c.getReviewedBy());
    }

    @Test
    void override_onlyFromReferred() {
        UnderwritingCase c = assessingCase();
        c.decide("userA", DecisionOutcome.APPROVED, null);
        assertThrows(BusinessException.class, () -> c.overrideDecision("userB", DecisionOutcome.DECLINED));
    }

    @Test
    void expire_onlyFromApproved() {
        UnderwritingCase c = assessingCase();
        c.decide("userA", DecisionOutcome.APPROVED, null);
        c.expire();
        assertEquals(UnderwritingStatus.EXPIRED, c.getStatus());
        assertTrue(c.getStatus().isTerminal());
    }

    @Test
    void consentMissing_startIsRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> UnderwritingCase.start(1001L, 0L, "W4-DEFAULT"));
        assertEquals("RF_UW_003", e.getErrorCode().getCode());
    }
}
