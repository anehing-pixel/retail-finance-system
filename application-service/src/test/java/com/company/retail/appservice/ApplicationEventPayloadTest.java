package com.company.retail.appservice;

import com.company.retail.appservice.app.RfEventPayloads;
import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.common.contract.OutboxPublisher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * APPLICATION_SUBMITTED payload 合规（INV-4）：只带业务主键，禁 PII。
 */
class ApplicationEventPayloadTest {

    static final class CapturingOutbox implements OutboxPublisher {
        final List<String> payloads = new ArrayList<>();

        @Override
        public void publish(String eventType, String aggregateKey, String payload) {
            payloads.add(payload);
        }
    }

    @Test
    void 提交事件_payload只带业务主键与状态_过白名单守卫() {
        CapturingOutbox outbox = new CapturingOutbox();
        RfEventPayloads.publish(outbox, OutboxPublisher.APPLICATION_SUBMITTED,
                "applicationId", "501", "trace-xyz",
                Map.of("status", "SUBMITTED", "scheme", "INSTALLMENT_CREDIT", "idempotencyKey", "IK-501"));
        assertEquals(1, outbox.payloads.size());
        String payload = outbox.payloads.get(0);
        EventPayloadGuard.validate(OutboxPublisher.APPLICATION_SUBMITTED, payload);
        assertTrue(payload.contains("\"applicationId\":\"501\""));
        // 客户信息只允许内部引用键（customerId 不在本事件 payload 里——客户属于 BC-2 域）
        assertEquals("applicationId", OutboxPublisher.bizKeyOf(OutboxPublisher.APPLICATION_SUBMITTED));
    }

    @Test
    void 提交事件_混入客户姓名_拒绝发布() {
        String payload = "{\"applicationId\":\"501\",\"traceId\":\"t\",\"eventType\":\"APPLICATION_SUBMITTED\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"fullName\":\"山田太郎\"}";
        assertThrows(IllegalArgumentException.class,
                () -> EventPayloadGuard.validate("APPLICATION_SUBMITTED", payload));
    }
}
