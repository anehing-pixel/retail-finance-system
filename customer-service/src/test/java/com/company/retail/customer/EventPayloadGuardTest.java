package com.company.retail.customer;

import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.customer.app.RfEventPayloads;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件 payload 白名单守卫（INV-4 / R09 §5.2 §5.3）：禁 PII，默认拒绝。
 * 生产方构造器只产白名单键；EventPayloadGuard 对 PII 键一律拒绝发布。
 */
class EventPayloadGuardTest {

    /** 捕获型假 outbox：只记录，不发外部（单测零基础设施）。 */
    static final class CapturingOutbox implements OutboxPublisher {
        final List<String> eventTypes = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();

        @Override
        public void publish(String eventType, String aggregateKey, String payload) {
            eventTypes.add(eventType);
            payloads.add(payload);
        }
    }

    @Test
    void 生产方payload_只含白名单键_含必填业务主键与traceId() {
        CapturingOutbox outbox = new CapturingOutbox();
        RfEventPayloads.publish(outbox, OutboxPublisher.CONSENT_GRANTED,
                "consentId", "42", "trace-abc", Map.of("status", "GRANTED"));
        assertEquals(1, outbox.eventTypes.size());
        String payload = outbox.payloads.get(0);
        // 发布前的守卫对已发布 payload 再跑一遍 = 白名单零违例
        EventPayloadGuard.validate(OutboxPublisher.CONSENT_GRANTED, payload);
        assertTrue(payload.contains("\"consentId\":\"42\""));
        assertTrue(payload.contains("\"traceId\":\"trace-abc\""));
        // 聚合键口径：CONSENT_* 的业务主键是 consentId（R04 §6）
        assertEquals("consentId", OutboxPublisher.bizKeyOf(OutboxPublisher.CONSENT_WITHDRAWN));
    }

    @Test
    void payload_含PII键_拒绝发布() {
        for (String piiKey : new String[]{"name", "fullName", "address", "birthDate", "phone",
                "email", "annualIncome", "employer", "myNumber", "customerName"}) {
            String payload = "{\"consentId\":\"1\",\"traceId\":\"t\",\"eventType\":\"CONSENT_GRANTED\","
                    + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"" + piiKey + "\":\"山田太郎\"}";
            assertThrows(IllegalArgumentException.class,
                    () -> EventPayloadGuard.validate("CONSENT_GRANTED", payload),
                    "禁键必须拒绝：" + piiKey);
        }
    }

    @Test
    void payload_白名单外任意键_默认拒绝() {
        String payload = "{\"consentId\":\"1\",\"traceId\":\"t\",\"eventType\":\"CONSENT_GRANTED\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"vinOwnerName\":\"someone\"}";
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate("CONSENT_GRANTED", payload));
    }

    @Test
    void payload_缺业务主键或缺traceId_拒绝() {
        String noTrace = "{\"consentId\":\"1\",\"eventType\":\"CONSENT_GRANTED\",\"occurredAt\":\"x\"}";
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate("CONSENT_GRANTED", noTrace));
        String noBizKey = "{\"traceId\":\"t\",\"eventType\":\"CONSENT_GRANTED\",\"occurredAt\":\"x\"}";
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate("CONSENT_GRANTED", noBizKey));
    }

    @Test
    void 生产方_业务主键字段与事件登记不一致_发布前拦截() {
        CapturingOutbox outbox = new CapturingOutbox();
        // CONSENT_GRANTED 的聚合键是 consentId；错传 verificationId 必须被拦
        assertThrows(IllegalArgumentException.class, () -> RfEventPayloads.publish(outbox,
                OutboxPublisher.CONSENT_GRANTED, "verificationId", "9", "t", Map.of()));
        assertEquals(0, outbox.eventTypes.size());
    }
}
