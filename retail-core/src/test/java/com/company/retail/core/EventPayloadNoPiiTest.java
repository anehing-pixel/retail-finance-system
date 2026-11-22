package com.company.retail.core;

import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.common.contract.OutboxPublisher;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件 payload 禁 PII（INV-4 / R09 §5；本波次生产的事件：CONTRACT_SIGNED、DISBURSEMENT_PAID）。
 */
class EventPayloadNoPiiTest {

    private static final ObjectMapperBridge BRIDGE = new ObjectMapperBridge();

    /** 两个服务事件 payload 的实际构建形态（LinkedHashMap → JSON 文本）。 */
    private static final class ObjectMapperBridge {
        String writeJson(Map<String, Object> payload) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static Map<String, Object> contractSignedPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", 1001L);
        payload.put("applicationId", 500L);
        payload.put("status", "COOLING_OFF");
        payload.put("traceId", "trace-abc");
        return payload;
    }

    private static Map<String, Object> disbursementPaidPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("disbursementId", 9001L);
        payload.put("loanId", 321L);
        payload.put("contractId", 1001L);
        payload.put("amount", 2_000_000L);
        payload.put("currency", "JPY");
        payload.put("status", "PAID");
        payload.put("traceId", "trace-abc");
        return payload;
    }

    @Test
    void contractSignedPayload_passesWhitelistGuard() {
        assertDoesNotThrow(() -> EventPayloadGuard.validate(
                OutboxPublisher.CONTRACT_SIGNED, BRIDGE.writeJson(contractSignedPayload())));
        assertEquals("contractId", OutboxPublisher.bizKeyOf(OutboxPublisher.CONTRACT_SIGNED));
    }

    @Test
    void disbursementPaidPayload_passesWhitelistGuard() {
        assertDoesNotThrow(() -> EventPayloadGuard.validate(
                OutboxPublisher.DISBURSEMENT_PAID, BRIDGE.writeJson(disbursementPaidPayload())));
        assertEquals("disbursementId", OutboxPublisher.bizKeyOf(OutboxPublisher.DISBURSEMENT_PAID));
    }

    @Test
    void piiKey_customerName_isRejectedByDefaultDeny() {
        Map<String, Object> payload = disbursementPaidPayload();
        payload.put("customerName", "山田太郎");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.DISBURSEMENT_PAID, BRIDGE.writeJson(payload)));
        assertTrue(ex.getMessage().contains("customerName"));
    }

    @Test
    void piiKey_annualIncome_isRejected() {
        Map<String, Object> payload = disbursementPaidPayload();
        payload.put("annualIncome", 5_000_000L);
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.DISBURSEMENT_PAID, BRIDGE.writeJson(payload)));
    }

    @Test
    void piiKey_bankAccount_isRejected() {
        Map<String, Object> payload = contractSignedPayload();
        payload.put("bankAccount", "1234567");
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.CONTRACT_SIGNED, BRIDGE.writeJson(payload)));
    }

    @Test
    void payloadWithoutTraceId_isRejected() {
        Map<String, Object> payload = disbursementPaidPayload();
        payload.remove("traceId");
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.DISBURSEMENT_PAID, BRIDGE.writeJson(payload)));
    }

    @Test
    void payloadWithoutBizKey_isRejected() {
        Map<String, Object> payload = disbursementPaidPayload();
        payload.remove("disbursementId");
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.DISBURSEMENT_PAID, BRIDGE.writeJson(payload)));
    }

    @Test
    void payloadWithAnyUnknownKey_isRejected() {
        Map<String, Object> payload = disbursementPaidPayload();
        payload.put("rawPayload", "{\"x\":1}");
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate(OutboxPublisher.DISBURSEMENT_PAID, BRIDGE.writeJson(payload)));
    }
}
