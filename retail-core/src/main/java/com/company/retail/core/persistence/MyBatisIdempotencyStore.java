package com.company.retail.core.persistence;

import com.company.platform.idempotency.IdempotencyRecord;
import com.company.platform.idempotency.IdempotencyStore;
import com.company.retail.common.persistence.RowValues;
import com.company.retail.core.mapper.IdempotencyMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link IdempotencyStore} 的 MyBatis 实现（表 {@code core.idempotency_records}，V12 迁移）。
 *
 * <p>唯一约束兜底并发插入（R00 §5 幂等口径：写接口 Idempotency-Key + DB 唯一索引双兜底）；
 * {@code response_body} 保存首次成功结果，重放场景原样返回。</p>
 */
@Repository
public class MyBatisIdempotencyStore implements IdempotencyStore {

    private final IdempotencyMapper mapper;

    public MyBatisIdempotencyStore(IdempotencyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        List<Map<String, Object>> rows = mapper.findRecord(key);
        return rows.stream().findFirst().map(row -> new IdempotencyRecord(
                RowValues.asString(row, "idempotency_key"),
                RowValues.asString(row, "request_fingerprint"),
                RowValues.asInstant(row, "created_at")));
    }

    @Override
    public void save(IdempotencyRecord record) {
        try {
            mapper.insertRecord(record.key(), record.requestFingerprint(),
                    Timestamp.from(record.createdAt() == null ? Instant.now() : record.createdAt()));
        } catch (DuplicateKeyException e) {
            // 并发同键插入：唯一约束兜底（谁先落库谁赢）；重放判定由 find() 语义承接
        }
    }

    /** 首次成功结果（重放返回用）。 */
    public void saveResponse(String key, String responseBody) {
        mapper.updateResponse(key, responseBody);
    }

    public Optional<String> findResponse(String key) {
        List<String> rows = mapper.findResponse(key);
        return rows.stream().findFirst();
    }
}
