package com.company.retail.core;

import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.common.contract.OutboxPublisher;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * W6 事件 payload 禁 PII（INV-4 / R09 §5）：REPAYMENT_RECEIVED / LOAN_SETTLED /
 * RELEASE_DOCUMENTS_ISSUED / TITLE_RELEASE_REGISTERED 全过白名单守卫；
 * 命中白名单外键（含 PII 键）一律拒绝发布。
 */
class W6EventPayloadNoPiiTest {

    private static String json(Map<String, Object> payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> repaymentReceivedPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentRef", "PM-ACC1-TX1");
        payload.put("loanId", 321L);
        payload.put("amount", 500000L);
        payload.put("currency", "JPY");
        payload.put("status", "ALLOCATED");
        payload.put("traceId", "trace-abc");
        return payload;
    }

    private static Map<String, Object> loanSettledPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", 321L);
        payload.put("contractId", 1001L);
        payload.put("status", "EARLY_SETTLED");
        payload.put("amount", 770000L);
        payload.put("currency", "JPY");
        payload.put("traceId", "trace-abc");
        return payload;
    }

    private static Map<String, Object> titleReleaseRegisteredPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseOrderId", 71L);
        payload.put("loanId", 321L);
        payload.put("titleId", 41L);
        payload.put("status", "CLOSED");
        payload.put("traceId", "trace-abc");
        return payload;
    }

    @Test
    void repaymentReceived_passesWhitelist_aggregateKeyIsPaymentRef() {
        assertDoesNotThrow(() -> EventPayloadGuard.validate(
                OutboxPublisher.REPAYMENT_RECEIVED, json(repaymentReceivedPayload())));
        assertEquals("paymentRef", OutboxPublisher.bizKeyOf(OutboxPublisher.REPAYMENT_RECEIVED));
    }

    @Test
    void loanSettled_passesWhitelist_aggregateKeyIsLoanId() {
        assertDoesNotThrow(() -> EventPayloadGuard.validate(
                OutboxPublisher.LOAN_SETTLED, json(loanSettledPayload())));
        assertEquals("loanId", OutboxPublisher.bizKeyOf(OutboxPublisher.LOAN_SETTLED));
    }

    @Test
    void titleReleaseRegistered_passesWhitelist_aggregateKeyIsReleaseOrderId() {
        assertDoesNotThrow(() -> EventPayloadGuard.validate(
                OutboxPublisher.TITLE_RELEASE_REGISTERED, json(titleReleaseRegisteredPayload())));
        assertEquals("releaseOrderId", OutboxPublisher.bizKeyOf(OutboxPublisher.TITLE_RELEASE_REGISTERED));
    }

    @Test
    void releaseDocumentsIssued_aggregateKeyIsReleaseOrderId() {
        assertEquals("releaseOrderId",
                OutboxPublisher.bizKeyOf(OutboxPublisher.RELEASE_DOCUMENTS_ISSUED));
    }

    @Test
    void customerNameInPayload_isRejected_defaultDeny() {
        Map<String, Object> payload = repaymentReceivedPayload();
        payload.put("customerName", "山田太郎"); // PII 禁键（R09 §5.3）
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.REPAYMENT_RECEIVED, json(payload)));
    }

    @Test
    void unregisteredKey_isRejected_defaultDeny() {
        Map<String, Object> payload = loanSettledPayload();
        payload.put("customerEmail", "a@example.com"); // 白名单外键一律拒绝
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.LOAN_SETTLED, json(payload)));
    }

    @Test
    void payloadWithoutTraceId_isRejected() {
        Map<String, Object> payload = repaymentReceivedPayload();
        payload.remove("traceId");
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.REPAYMENT_RECEIVED, json(payload)));
    }
}
