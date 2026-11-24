package com.company.retail.underwriting.application;

import com.company.platform.event.DomainEvent;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.underwriting.mapper.OutboundTaskMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P0-7：DMS 推送失败落 outbound_tasks 时写结构化 payload + 首次退避。
 */
class DmsResultNotificationConsumerTest {

    private final RecordingMapper mapper = new RecordingMapper();

    private static DomainEvent event(String payload) {
        return new DomainEvent(1L, OutboxPublisher.UNDERWRITING_APPROVED, "9", payload, Instant.now());
    }

    private static String payload() {
        return "{\"caseId\":9,\"applicationId\":10,\"status\":\"APPROVED\","
                + "\"reasonCode\":\"RC-1\",\"traceId\":\"t-1\"}";
    }

    @Test
    void successDoesNotCreateTask() {
        DmsResultNotificationConsumer consumer = new DmsResultNotificationConsumer(
                (caseId, applicationId, decision, reason, trace) -> "DMS-1", mapper);

        consumer.consume(event(payload()));

        assertEquals(0, mapper.inserts.size());
    }

    @Test
    void failureCreatesStructuredPendingTaskWithFirstBackoff() {
        DmsResultNotificationConsumer consumer = new DmsResultNotificationConsumer(
                (caseId, applicationId, decision, reason, trace) -> null, mapper);

        consumer.consume(event(payload()));

        assertEquals(1, mapper.inserts.size());
        Insert insert = mapper.inserts.get(0);
        assertEquals("DMS_RESULT_NOTIFY", insert.taskType());
        assertEquals("9|10|APPROVED|RC-1", insert.payloadRef());
        assertEquals("t-1", insert.traceId());
        assertEquals(60, insert.nextRetrySeconds());
    }

    @Test
    void stubModeDoesNothing() {
        DmsResultNotificationConsumer consumer = new DmsResultNotificationConsumer(null, mapper);

        consumer.consume(event(payload()));

        assertEquals(0, mapper.inserts.size());
    }

    @Test
    void missingCaseIdStillFailsFast() {
        DmsResultNotificationConsumer consumer = new DmsResultNotificationConsumer(
                (caseId, applicationId, decision, reason, trace) -> null, mapper);

        assertThrows(IllegalStateException.class,
                () -> consumer.consume(event("{\"status\":\"APPROVED\"}")));
    }

    private record Insert(String taskType, String payloadRef, String traceId, long nextRetrySeconds) {
    }

    static final class RecordingMapper implements OutboundTaskMapper {

        private final List<Insert> inserts = new java.util.ArrayList<>();

        @Override
        public int insertPending(String taskType, String payloadRef, String traceId, long nextRetrySeconds) {
            inserts.add(new Insert(taskType, payloadRef, traceId, nextRetrySeconds));
            return 1;
        }

        @Override
        public int reapStale(long leaseSeconds, int maxRetries, int limit) {
            return 0;
        }

        @Override
        public List<Map<String, Object>> claimDue(int limit) {
            return List.of();
        }

        @Override
        public int markDone(long taskId, int version) {
            return 1;
        }

        @Override
        public int markRetry(long taskId, int version, int retryCount, long delaySeconds) {
            return 1;
        }

        @Override
        public int markDead(long taskId, int version) {
            return 1;
        }

        @Override
        public Map<String, Object> findById(long taskId) {
            return null;
        }

        @Override
        public int resetToPending(long taskId) {
            return 1;
        }
    }
}
