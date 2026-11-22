package com.company.retail.appservice.repo;

import com.company.platform.idempotency.IdempotencyRecord;
import com.company.platform.idempotency.IdempotencyStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 申请幂等存储（platform {@link IdempotencyStore} 实现）：落在 core.applications 的
 * idempotency_key（uk_app_idempotency 唯一索引）+ request_fingerprint（V10 增列）上。
 * 不新开表——幂等记录与业务行同生共死，天然同事务。
 */
@Repository
public class ApplicationIdempotencyStore implements IdempotencyStore {

    private final JdbcTemplate jdbc;

    public ApplicationIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        var rows = jdbc.queryForList(
                "SELECT idempotency_key, request_fingerprint, created_at FROM core.applications "
                        + "WHERE idempotency_key = ?", key);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var row = rows.get(0);
        Object createdAt = row.get("created_at");
        return Optional.of(new IdempotencyRecord(
                (String) row.get("idempotency_key"),
                (String) row.get("request_fingerprint"),
                createdAt == null ? null : ((Timestamp) createdAt).toInstant()));
    }

    /** 业务插入即写入指纹（同事务）；本方法仅用于显式补写场景（如按 no 回填）。 */
    @Override
    public void save(IdempotencyRecord record) {
        jdbc.update(
                "UPDATE core.applications SET request_fingerprint = ? WHERE idempotency_key = ?",
                record.requestFingerprint(), record.key());
    }

    /** 重放定位：按键取首次受理的申请。 */
    public Optional<Long> findApplicationIdByKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        var ids = jdbc.queryForList(
                "SELECT application_id FROM core.applications WHERE idempotency_key = ?", Long.class, key);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }
}
