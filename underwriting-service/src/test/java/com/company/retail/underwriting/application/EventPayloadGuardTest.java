package com.company.retail.underwriting.application;

import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.common.contract.OutboxPublisher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件 payload 白名单单测（INV-4 / R09 §5.2 §5.3，ADR-RF-007）：
 * payload 禁 PII——命中禁止键或白名单外键即拒绝发布。
 */
class EventPayloadGuardTest {

    private static String decisionPayload() {
        return "{\"caseId\":1001,\"applicationId\":2002,\"status\":\"APPROVED\","
                + "\"outcomeCode\":\"APPROVED\",\"ruleVersion\":\"W4-DEFAULT\","
                + "\"occurredAt\":\"2026-11-22T10:00:00+09:00\",\"schemaVersion\":\"1.0\","
                + "\"traceId\":\"trace-1\"}";
    }

    @Test
    void whitelistPayload_passes() {
        EventPayloadGuard.validate(OutboxPublisher.UNDERWRITING_APPROVED, decisionPayload());
    }

    @Test
    void piiKey_applicantName_isRejected() {
        String pii = "{\"caseId\":1001,\"applicantName\":\"山田太郎\",\"traceId\":\"t\"}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.UNDERWRITING_APPROVED, pii));
        assertTrue(e.getMessage().contains("applicantName"));
    }

    @Test
    void piiKey_annualIncome_isRejected() {
        String pii = "{\"caseId\":1001,\"annualIncome\":5000000,\"traceId\":\"t\"}";
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.UNDERWRITING_APPROVED, pii));
    }

    @Test
    void piiKey_rawPayload_isRejected() {
        String pii = "{\"caseId\":1001,\"rawPayload\":\"{encrypted blob}\",\"traceId\":\"t\"}";
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.CREDIT_BUREAU_REPORT_SENT, pii));
    }

    @Test
    void unknownKey_isRejectedByDefault() {
        String payload = "{\"caseId\":1001,\"someRandomKey\":1,\"traceId\":\"t\"}";
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.UNDERWRITING_APPROVED, payload));
    }

    @Test
    void missingBizKey_or_traceId_isRejected() {
        String noCaseId = "{\"status\":\"APPROVED\",\"traceId\":\"t\"}";
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.UNDERWRITING_APPROVED, noCaseId));
        String noTrace = "{\"caseId\":1001}";
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.UNDERWRITING_APPROVED, noTrace));
    }

    @Test
    void bizKeyOf_mappingMatchesR09() {
        assertEquals("caseId", OutboxPublisher.bizKeyOf(OutboxPublisher.UNDERWRITING_APPROVED));
        assertEquals("caseId", OutboxPublisher.bizKeyOf(OutboxPublisher.UNDERWRITING_DECLINED));
        assertEquals("inquiryId", OutboxPublisher.bizKeyOf(OutboxPublisher.CREDIT_INQUIRY_COMPLETED));
        assertEquals("reportId", OutboxPublisher.bizKeyOf(OutboxPublisher.CREDIT_BUREAU_REPORT_SENT));
    }
}
