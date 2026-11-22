package com.company.retail.bureau.application;

import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.common.contract.OutboxPublisher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 征信事件 payload 白名单单测（INV-4 / R09 §5.3：reportId 等业务主键 OK，
 * 客户姓名/收入/账户等 PII 一律拒绝发布）。
 */
class BureauEventPayloadGuardTest {

    private static String reportPayload() {
        return "{\"reportId\":8001,\"status\":\"SENT\",\"source\":\"AUTO\","
                + "\"occurredAt\":\"2026-11-22T10:00:00+09:00\",\"schemaVersion\":\"1.0\","
                + "\"traceId\":\"trace-2\"}";
    }

    @Test
    void reportSentPayload_whitelistKeys_passes() {
        EventPayloadGuard.validate(OutboxPublisher.CREDIT_BUREAU_REPORT_SENT, reportPayload());
    }

    @Test
    void reportPayload_withCustomerName_isRejected() {
        String pii = "{\"reportId\":8001,\"customerName\":\"山田太郎\",\"traceId\":\"t\"}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.CREDIT_BUREAU_REPORT_SENT, pii));
        assertTrue(e.getMessage().contains("customerName"));
    }

    @Test
    void reportPayload_withAccountNo_isRejected() {
        String pii = "{\"reportId\":8001,\"accountNo\":\"1234567\",\"traceId\":\"t\"}";
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.CREDIT_BUREAU_REPORT_SENT, pii));
    }

    @Test
    void inquiryPayload_withRawMessage_isRejected() {
        // 机构报文原文绝不进事件 payload（报文只进留痕表）
        String pii = "{\"inquiryId\":9001,\"rawPayload\":\"{...}\" ,\"traceId\":\"t\"}";
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.CREDIT_INQUIRY_COMPLETED, pii));
    }
}
