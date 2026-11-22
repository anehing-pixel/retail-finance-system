package com.company.retail.bureau.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 征信报送策略点单测（C-05 裁决值：CIC+JICC 双机构查询、报送主 CIC、T+1、异议 30 日）。
 */
class DefaultCreditBureauReportingPolicyTest {

    private final DefaultCreditBureauReportingPolicy policy =
            new DefaultCreditBureauReportingPolicy(true, "CIC,JICC", "CIC", 30, 1);

    @Test
    void inquiryCombination_isCicAndJicc_perC05() {
        assertEquals(2, policy.inquiryTargets().size());
        assertTrue(policy.inquiryTargets().contains(CreditBureau.CIC));
        assertTrue(policy.inquiryTargets().contains(CreditBureau.JICC));
        assertFalse(policy.inquiryTargets().contains(CreditBureau.ZENGIN));
    }

    @Test
    void primaryReportBureau_isCic_perC05() {
        assertEquals(CreditBureau.CIC, policy.primaryReportBureau());
    }

    @Test
    void disputeSla_is30Days_perC05() {
        assertEquals(30, policy.disputeSlaDays());
    }

    @Test
    void reportBizDate_isT1_perC05() {
        assertEquals(LocalDate.of(2026, 11, 23), policy.nextReportBizDate(LocalDate.of(2026, 11, 22)));
    }

    @Test
    void disabledSwitch_flipsEnabledFlag() {
        DefaultCreditBureauReportingPolicy disabled =
                new DefaultCreditBureauReportingPolicy(false, "CIC,JICC", "CIC", 30, 1);
        assertFalse(disabled.enabled());
        assertTrue(policy.enabled());
    }

    @Test
    void configurationOverride_changesRulings() {
        DefaultCreditBureauReportingPolicy overridden =
                new DefaultCreditBureauReportingPolicy(true, "JICC", "JICC", 60, 2);
        assertEquals(CreditBureau.JICC, overridden.primaryReportBureau());
        assertEquals(60, overridden.disputeSlaDays());
        assertEquals(LocalDate.of(2026, 11, 24), overridden.nextReportBizDate(LocalDate.of(2026, 11, 22)));
    }

    @Test
    void emptyCombination_isRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new DefaultCreditBureauReportingPolicy(true, " ", "CIC", 30, 1));
    }
}
