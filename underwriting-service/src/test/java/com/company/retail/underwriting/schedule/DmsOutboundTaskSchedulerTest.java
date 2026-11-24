package com.company.retail.underwriting.schedule;

import com.company.retail.common.outbound.OutboundProperties;
import com.company.retail.underwriting.integration.DmsResultSender;
import com.company.retail.underwriting.mapper.OutboundTaskMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-7：outbound_tasks 状态机——成功 DONE，失败按 1m/5m/30m/2h/6h 退避，5 次后 DEAD。
 */
class DmsOutboundTaskSchedulerTest {

    private final FakeMapper mapper = new FakeMapper();
    private final RecordingSender sender = new RecordingSender();
    private final OutboundProperties properties = new OutboundProperties();

    private DmsOutboundTaskScheduler scheduler() {
        return new DmsOutboundTaskScheduler(mapper, sender, properties.getTasks());
    }

    private static Map<String, Object> task(long id, String type, String payloadRef, int retryCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("task_id", id);
        row.put("task_type", type);
        row.put("payload_ref", payloadRef);
        row.put("retry_count", retryCount);
        row.put("trace_id", "t-" + id);
        row.put("version", 3);
        return row;
    }

    @Test
    void successMarksDone() {
        mapper.due.add(task(1, "DMS_RESULT_NOTIFY", "9|10|APPROVED|RC", 0));

        scheduler().poll();

        assertEquals(List.of(1L), mapper.done);
        assertTrue(mapper.retries.isEmpty());
        assertEquals(1, mapper.reapCalls);
        assertEquals(1, mapper.claimCalls);
        assertEquals(1, sender.calls.size());
    }

    @Test
    void failureSchedulesFirstBackoff() {
        mapper.due.add(task(2, "DMS_RESULT_NOTIFY", "9|10|APPROVED|RC", 0));
        sender.result = null;

        scheduler().poll();

        assertEquals(1, mapper.retries.size());
        Retry retry = mapper.retries.get(0);
        assertEquals(2L, retry.taskId());
        assertEquals(1, retry.retryCount());
        assertEquals(Duration.ofMinutes(1).toSeconds(), retry.delaySeconds());
    }

    @Test
    void fifthAttemptFailureGoesDead() {
        mapper.due.add(task(3, "DMS_RESULT_NOTIFY", "9|10|APPROVED|RC", 4));
        sender.result = null;

        scheduler().poll();

        // retry_count=4 表示已重试 4 次；第 5 次失败达到上限 → DEAD
        assertEquals(List.of(3L), mapper.dead);
        assertTrue(mapper.retries.isEmpty());
    }

    @Test
    void fourthAttemptFailureSchedulesTwoHours() {
        mapper.due.add(task(4, "DMS_RESULT_NOTIFY", "9|10|APPROVED|RC", 3));
        sender.result = null;

        scheduler().poll();

        Retry retry = mapper.retries.get(0);
        assertEquals(4, retry.retryCount());
        assertEquals(Duration.ofHours(2).toSeconds(), retry.delaySeconds());
    }

    @Test
    void unknownTaskTypeGoesDeadWithoutCallingSender() {
        mapper.due.add(task(5, "UNKNOWN", "x", 0));

        scheduler().poll();

        assertEquals(List.of(5L), mapper.dead);
        assertTrue(sender.calls.isEmpty());
    }

    @Test
    void malformedPayloadGoesDeadWithoutCallingSender() {
        mapper.due.add(task(6, "DMS_RESULT_NOTIFY", "not-a-payload", 0));

        scheduler().poll();

        assertEquals(List.of(6L), mapper.dead);
        assertTrue(sender.calls.isEmpty());
    }

    @Test
    void senderExceptionDoesNotBubbleAndSchedulesRetry() {
        mapper.due.add(task(7, "DMS_RESULT_NOTIFY", "9|10|APPROVED|RC", 0));
        sender.failure = new IllegalStateException("boom");

        scheduler().poll();

        assertEquals(1, mapper.retries.size());
        assertTrue(mapper.dead.isEmpty());
    }

    static final class Retry {
        private final long taskId;
        private final int retryCount;
        private final long delaySeconds;

        Retry(long taskId, int retryCount, long delaySeconds) {
            this.taskId = taskId;
            this.retryCount = retryCount;
            this.delaySeconds = delaySeconds;
        }

        long taskId() {
            return taskId;
        }

        int retryCount() {
            return retryCount;
        }

        long delaySeconds() {
            return delaySeconds;
        }
    }

    static final class RecordingSender implements DmsResultSender {
        private final List<Long> calls = new CopyOnWriteArrayList<>();
        private volatile String result = "DMS-1";
        private volatile RuntimeException failure;

        @Override
        public String sendResult(long caseId, long applicationId, String decision, String reasonCode, String traceId) {
            calls.add(caseId);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    static final class FakeMapper implements OutboundTaskMapper {

        private final List<Map<String, Object>> due = new ArrayList<>();
        private final List<Long> done = new ArrayList<>();
        private final List<Long> dead = new ArrayList<>();
        private final List<Retry> retries = new ArrayList<>();
        private int reapCalls;
        private int claimCalls;

        @Override
        public int insertPending(String taskType, String payloadRef, String traceId, long nextRetrySeconds) {
            return 1;
        }

        @Override
        public int reapStale(long leaseSeconds, int maxRetries, int limit) {
            reapCalls++;
            return 0;
        }

        @Override
        public List<Map<String, Object>> claimDue(int limit) {
            claimCalls++;
            return new ArrayList<>(due);
        }

        @Override
        public int markDone(long taskId, int version) {
            done.add(taskId);
            return 1;
        }

        @Override
        public int markRetry(long taskId, int version, int retryCount, long delaySeconds) {
            retries.add(new Retry(taskId, retryCount, delaySeconds));
            return 1;
        }

        @Override
        public int markDead(long taskId, int version) {
            dead.add(taskId);
            return 1;
        }

        @Override
        public Map<String, Object> findById(long taskId) {
            Map<String, Object> row = new HashMap<>();
            row.put("task_id", taskId);
            return row;
        }

        @Override
        public int resetToPending(long taskId) {
            return 1;
        }
    }
}
