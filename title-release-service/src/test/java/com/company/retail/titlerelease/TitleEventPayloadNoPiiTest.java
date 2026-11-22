package com.company.retail.titlerelease;

import com.company.platform.event.DomainEvent;
import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.common.contract.OutboxPublisher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * W6 title-release 事件 payload 禁 PII（INV-4）+ 消费方 payload 解析。
 */
class TitleEventPayloadNoPiiTest {

    private static String json(Map<String, Object> payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void titleRetentionRegisteredPayload_passesGuard() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("titleId", 41L);
        payload.put("loanId", 321L);
        payload.put("status", "REGISTERED");
        payload.put("traceId", "trace-abc");
        assertDoesNotThrow(() -> EventPayloadGuard.validate(
                OutboxPublisher.TITLE_RETENTION_REGISTERED, json(payload)));
        assertEquals("titleId", OutboxPublisher.bizKeyOf(OutboxPublisher.TITLE_RETENTION_REGISTERED));
    }

    @Test
    void releaseDocumentsIssuedPayload_passesGuard() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseOrderId", 71L);
        payload.put("loanId", 321L);
        payload.put("status", "DOCUMENTS_ISSUED");
        payload.put("traceId", "trace-abc");
        assertDoesNotThrow(() -> EventPayloadGuard.validate(
                OutboxPublisher.RELEASE_DOCUMENTS_ISSUED, json(payload)));
    }

    @Test
    void titleReleaseRegisteredPayload_passesGuard_onlyAfterExternalReceipt() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseOrderId", 71L);
        payload.put("loanId", 321L);
        payload.put("titleId", 41L);
        payload.put("status", "CLOSED");
        payload.put("traceId", "trace-abc");
        assertDoesNotThrow(() -> EventPayloadGuard.validate(
                OutboxPublisher.TITLE_RELEASE_REGISTERED, json(payload)));
    }

    @Test
    void vinOwnerNameInPayload_isRejected() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseOrderId", 71L);
        payload.put("loanId", 321L);
        payload.put("vinOwnerName", "山田太郎"); // PII 禁键（R09 §5.3）
        payload.put("traceId", "trace-abc");
        assertThrows(IllegalArgumentException.class, () -> EventPayloadGuard.validate(
                OutboxPublisher.TITLE_RELEASE_REGISTERED, json(payload)));
    }

    @Test
    void consumerReadsLoanId_fromSettledEventPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", 321L);
        payload.put("contractId", 1001L);
        payload.put("status", "EARLY_SETTLED");
        payload.put("traceId", "trace-abc");
        DomainEvent event = new DomainEvent(9001L, OutboxPublisher.LOAN_SETTLED, "321",
                json(payload), Instant.parse("2026-11-22T00:00:00Z"));
        assertEquals(321L, event.longField("loanId"));
    }
}
