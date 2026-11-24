package com.company.retail.underwriting.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.underwriting.mapper.OutboundTaskMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P0-7：人工重触发——DEAD→PENDING；PENDING/RETRY 幂等；DONE/IN_PROGRESS 冲突。
 */
class OutboundTaskRetryServiceTest {

    private final FakeMapper mapper = new FakeMapper();

    private OutboundTaskRetryService service() {
        return new OutboundTaskRetryService(mapper);
    }

    @Test
    void deadTaskIsResetToPending() {
        mapper.row = Map.of("task_id", 1L, "status", "DEAD");

        assertEquals("PENDING", service().retry(1L));
        assertEquals(1, mapper.resetCalls);
    }

    @Test
    void pendingTaskIsIdempotent() {
        mapper.row = Map.of("task_id", 2L, "status", "PENDING");

        assertEquals("PENDING", service().retry(2L));
        assertEquals(0, mapper.resetCalls);
    }

    @Test
    void retryTaskIsIdempotent() {
        mapper.row = Map.of("task_id", 3L, "status", "RETRY");

        assertEquals("RETRY", service().retry(3L));
        assertEquals(0, mapper.resetCalls);
    }

    @Test
    void doneTaskConflicts() {
        mapper.row = Map.of("task_id", 4L, "status", "DONE");

        BusinessException exception = assertThrows(BusinessException.class, () -> service().retry(4L));
        assertEquals(RFErrorCode.RF_CONFLICT_001, exception.getErrorCode());
    }

    @Test
    void inProgressTaskConflicts() {
        mapper.row = Map.of("task_id", 5L, "status", "IN_PROGRESS");

        BusinessException exception = assertThrows(BusinessException.class, () -> service().retry(5L));
        assertEquals(RFErrorCode.RF_CONFLICT_001, exception.getErrorCode());
    }

    @Test
    void missingTaskIsValidationError() {
        mapper.row = null;

        BusinessException exception = assertThrows(BusinessException.class, () -> service().retry(9L));
        assertEquals(RFErrorCode.RF_VALID_001, exception.getErrorCode());
    }

    @Test
    void concurrentResetConflicts() {
        mapper.row = Map.of("task_id", 6L, "status", "DEAD");
        mapper.resetResult = 0;

        BusinessException exception = assertThrows(BusinessException.class, () -> service().retry(6L));
        assertEquals(RFErrorCode.RF_CONFLICT_001, exception.getErrorCode());
    }

    static final class FakeMapper implements OutboundTaskMapper {

        private Map<String, Object> row;
        private int resetCalls;
        private int resetResult = 1;

        @Override
        public int insertPending(String taskType, String payloadRef, String traceId, long nextRetrySeconds) {
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
            return row;
        }

        @Override
        public int resetToPending(long taskId) {
            resetCalls++;
            return resetResult;
        }
    }
}
