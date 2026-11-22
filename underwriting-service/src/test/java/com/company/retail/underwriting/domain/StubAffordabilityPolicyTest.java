package com.company.retail.underwriting.domain;

import com.company.platform.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 支付能力评估挡板单测（C-03 框架口径：不实现官方公式，只验证策略点行为契约）。
 */
class StubAffordabilityPolicyTest {

    private final StubAffordabilityPolicy policy = new StubAffordabilityPolicy(true);

    private AffordabilityInput input(String incomeRef, long debt, long threshold, long requested) {
        return new AffordabilityInput("INSTALLMENT_CREDIT", incomeRef, debt, threshold, requested);
    }

    @Test
    void withinThreshold_passes() {
        AssessmentOutput out = policy.assess(input("ENC-REF-1", 500_000L, 3_000_000L, 2_000_000L));
        assertEquals(AssessmentResult.PASS, out.result());
    }

    @Test
    void overThreshold_fails_withRfUw002() {
        AssessmentOutput out = policy.assess(input("ENC-REF-1", 500_000L, 2_000_000L, 3_000_000L));
        assertEquals(AssessmentResult.FAIL, out.result());
        assertEquals("OVER_AFFORDABILITY_THRESHOLD", out.reasonCode());
    }

    @Test
    void missingIncomeDocument_goesToManualReviewChannel() {
        AssessmentOutput out = policy.assess(input(null, 0L, 3_000_000L, 1_000_000L));
        assertEquals(AssessmentResult.REFER, out.result());
        assertEquals("INCOME_DOCUMENT_MISSING", out.reasonCode());
    }

    @Test
    void policySwitchDisabled_goesToManualReviewChannel_notSilentPass() {
        StubAffordabilityPolicy disabled = new StubAffordabilityPolicy(false);
        AssessmentOutput out = disabled.assess(input("ENC-REF-1", 0L, 3_000_000L, 1_000_000L));
        assertEquals(AssessmentResult.REFER, out.result());
        assertEquals("POLICY_DISABLED", out.reasonCode());
    }

    @Test
    void schemeMissing_isRejected_perC01NoDefault() {
        AffordabilityInput noScheme = new AffordabilityInput(null, "ENC-REF-1", 0L, 3_000_000L, 1_000_000L);
        BusinessException e = assertThrows(BusinessException.class, () -> policy.assess(noScheme));
        assertEquals("RF_DMS_003", e.getErrorCode().getCode());
    }
}
