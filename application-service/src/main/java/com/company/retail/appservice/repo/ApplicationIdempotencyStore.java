package com.company.retail.appservice.repo;

import com.company.platform.idempotency.IdempotencyRecord;
import com.company.platform.idempotency.IdempotencyStore;
import com.company.retail.appservice.mapper.IdempotencyMapper;
import com.company.retail.common.persistence.RowValues;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 申请幂等存储（platform {@link IdempotencyStore} 实现）：落在 core.applications 的
 * idempotency_key（uk_app_idempotency 唯一索引）+ request_fingerprint（V10 增列）上。
 * 不新开表——幂等记录与业务行同生共死，天然同事务。
 */
@Repository
public class ApplicationIdempotencyStore implements IdempotencyStore {

    private final IdempotencyMapper mapper;

    public ApplicationIdempotencyStore(IdempotencyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = mapper.findIdempotencyRows(key);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        Timestamp createdAt = RowValues.asTimestamp(row, "created_at");
        return Optional.of(new IdempotencyRecord(
                RowValues.asString(row, "idempotency_key"),
                RowValues.asString(row, "request_fingerprint"),
                createdAt == null ? null : createdAt.toInstant()));
    }

    /** 业务插入即写入指纹（同事务）；本方法仅用于显式补写场景（如按 no 回填）。 */
    @Override
    public void save(IdempotencyRecord record) {
        mapper.updateRequestFingerprint(record.requestFingerprint(), record.key());
    }

    /** 重放定位：按键取首次受理的申请。 */
    public Optional<Long> findApplicationIdByKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        List<Long> ids = mapper.findApplicationIdsByKey(key);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }
}
