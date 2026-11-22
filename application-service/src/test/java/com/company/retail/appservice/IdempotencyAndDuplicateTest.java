package com.company.retail.appservice;

import com.company.platform.exception.BusinessException;
import com.company.platform.idempotency.IdempotencyGuard;
import com.company.platform.idempotency.IdempotencyRecord;
import com.company.platform.idempotency.IdempotencyStore;
import com.company.retail.appservice.domain.DuplicateApplicationDetector;
import com.company.retail.domain.exception.RFErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等守卫与重复申请检测（R09 §6 / R04 §5.2）。
 * 幂等存储用内存假实现（键唯一语义与 DB 唯一索引一致）。
 */
class IdempotencyAndDuplicateTest {

    /** 内存假存储：行为对齐 ApplicationIdempotencyStore（DB 唯一索引兜底语义）。 */
    static final class InMemoryIdempotencyStore implements IdempotencyStore {
        final Map<String, IdempotencyRecord> rows = new HashMap<>();

        @Override
        public Optional<IdempotencyRecord> find(String key) {
            return Optional.ofNullable(rows.get(key));
        }

        @Override
        public void save(IdempotencyRecord record) {
            rows.put(record.key(), record);
        }
    }

    @Test
    void 幂等_同键同指纹_重放放行返回既有记录() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        String key = "IK-001";
        String fingerprint = IdempotencyGuard.sha256Fingerprint("{\"customerId\":100,\"vin\":\"VIN1\"}");
        store.save(new IdempotencyRecord(key, fingerprint, Instant.now()));

        Optional<IdempotencyRecord> replay = IdempotencyGuard.checkOrThrow(
                store, key, fingerprint, RFErrorCode.RF_CONFLICT_001);
        assertTrue(replay.isPresent());
        assertEquals(key, replay.get().key());
    }

    @Test
    void 幂等_同键异指纹_冲突拒绝RF_CONFLICT_001() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        String key = "IK-002";
        store.save(new IdempotencyRecord(key,
                IdempotencyGuard.sha256Fingerprint("{\"customerId\":100,\"vin\":\"VIN1\"}"), Instant.now()));

        String otherFingerprint = IdempotencyGuard.sha256Fingerprint("{\"customerId\":200,\"vin\":\"VIN2\"}");
        BusinessException ex = assertThrows(BusinessException.class, () -> IdempotencyGuard.checkOrThrow(
                store, key, otherFingerprint, RFErrorCode.RF_CONFLICT_001));
        assertEquals(RFErrorCode.RF_CONFLICT_001, ex.getErrorCode());
        assertEquals(409, ex.getErrorCode().getHttpStatus());
    }

    @Test
    void 幂等_首请求_放行且不落记录() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        Optional<IdempotencyRecord> existing = IdempotencyGuard.checkOrThrow(
                store, "IK-003", "fp", RFErrorCode.RF_CONFLICT_001);
        assertTrue(existing.isEmpty());
        assertTrue(store.rows.isEmpty());
    }

    @Test
    void 幂等_指纹对内容敏感_内容不同指纹不同() {
        assertFalse(IdempotencyGuard.sha256Fingerprint("{\"a\":1}")
                .equals(IdempotencyGuard.sha256Fingerprint("{\"a\":2}")));
    }

    @Test
    void 重复申请_同一人同一車窗口期内命中() {
        Instant now = Instant.now();
        // 窗口 72h：窗口内提交 → 命中
        assertTrue(DuplicateApplicationDetector.isDuplicate(now.minus(Duration.ofHours(1)), now, Duration.ofHours(72)));
        assertTrue(DuplicateApplicationDetector.isDuplicate(now.minus(Duration.ofHours(71)), now, Duration.ofHours(72)));
        // 窗口外 → 放行
        assertFalse(DuplicateApplicationDetector.isDuplicate(now.minus(Duration.ofHours(73)), now, Duration.ofHours(72)));
        // 无在途记录 / 非法窗口 → 放行
        assertFalse(DuplicateApplicationDetector.isDuplicate(null, now, Duration.ofHours(72)));
        assertFalse(DuplicateApplicationDetector.isDuplicate(now, now, Duration.ZERO));
    }
}
