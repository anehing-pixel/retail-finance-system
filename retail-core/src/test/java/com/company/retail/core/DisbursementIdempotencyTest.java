package com.company.retail.core;

import com.company.platform.exception.BusinessException;
import com.company.platform.idempotency.IdempotencyGuard;
import com.company.platform.idempotency.IdempotencyRecord;
import com.company.retail.domain.exception.RFErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代付幂等守卫（platform {@link IdempotencyGuard}；W5 前半的三层幂等第①层）。
 */
class DisbursementIdempotencyTest {

    /** 内存幂等存储（挡板；生产实现 JdbcIdempotencyStore 由唯一索引兜底并发）。 */
    private static final class InMemoryIdempotencyStore implements com.company.platform.idempotency.IdempotencyStore {
        final Map<String, IdempotencyRecord> records = new HashMap<>();
        final Map<String, String> responses = new HashMap<>();

        @Override
        public Optional<IdempotencyRecord> find(String key) {
            return Optional.ofNullable(records.get(key));
        }

        @Override
        public void save(IdempotencyRecord record) {
            records.putIfAbsent(record.key(), record);
        }
    }

    private static final String CANONICAL = "loanId=1|contractId=500|underwritingCaseId=9|payeeAccountRef=REF-1";

    @Test
    void fingerprint_isDeterministicForSameContent() {
        assertEquals(IdempotencyGuard.sha256Fingerprint(CANONICAL),
                IdempotencyGuard.sha256Fingerprint(CANONICAL));
        assertFalse(IdempotencyGuard.sha256Fingerprint(CANONICAL)
                .equals(IdempotencyGuard.sha256Fingerprint("loanId=2|contractId=500|underwritingCaseId=9|payeeAccountRef=REF-1")));
    }

    @Test
    void firstRequest_passesWithNoExistingRecord() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        Optional<IdempotencyRecord> existing = IdempotencyGuard.checkOrThrow(
                store, "KEY-1", IdempotencyGuard.sha256Fingerprint(CANONICAL), RFErrorCode.RF_CONFLICT_001);
        assertTrue(existing.isEmpty());
    }

    @Test
    void sameKeySameFingerprint_isReplay_returnsFirstRecord() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        String fp = IdempotencyGuard.sha256Fingerprint(CANONICAL);
        IdempotencyGuard.checkOrThrow(store, "KEY-1", fp, RFErrorCode.RF_CONFLICT_001);
        store.save(new IdempotencyRecord("KEY-1", fp, Instant.parse("2026-11-22T00:00:00Z")));

        Optional<IdempotencyRecord> replay = IdempotencyGuard.checkOrThrow(
                store, "KEY-1", fp, RFErrorCode.RF_CONFLICT_001);
        // 重放：返回首次记录，不重复执行业务（调用方按既有记录返回首次结果）
        assertTrue(replay.isPresent());
        assertEquals(fp, replay.get().requestFingerprint());
    }

    @Test
    void sameKeyDifferentFingerprint_conflict_rfConflict001() {
        // 键同体不同 → RF_CONFLICT_001（R00 §5：同一键两次请求内容不同即冲突）
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        String fp1 = IdempotencyGuard.sha256Fingerprint(CANONICAL);
        String fp2 = IdempotencyGuard.sha256Fingerprint(
                "loanId=1|contractId=500|underwritingCaseId=9|payeeAccountRef=REF-2");
        store.save(new IdempotencyRecord("KEY-1", fp1, Instant.now()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> IdempotencyGuard.checkOrThrow(store, "KEY-1", fp2, RFErrorCode.RF_CONFLICT_001));
        assertEquals(RFErrorCode.RF_CONFLICT_001, ex.getErrorCode());
    }
}
