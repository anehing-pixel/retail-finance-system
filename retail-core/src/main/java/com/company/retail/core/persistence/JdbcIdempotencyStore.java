package com.company.retail.core.persistence;

import com.company.platform.idempotency.IdempotencyRecord;
import com.company.platform.idempotency.IdempotencyStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link IdempotencyStore} 的 JDBC 实现（表 {@code core.idempotency_records}，V12 迁移）。
 *
 * <p>唯一约束兜底并发插入（R00 §5 幂等口径：写接口 Idempotency-Key + DB 唯一索引双兜底）；
 * {@code response_body} 保存首次成功结果，重放场景原样返回。</p>
 */
@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcTemplate jdbc;

    public JdbcIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        List<IdempotencyRecord> rows = jdbc.query(
                "SELECT idempotency_key, request_fingerprint, created_at FROM core.idempotency_records "
                        + "WHERE idempotency_key = ?",
                (rs, i) -> new IdempotencyRecord(
                        rs.getString("idempotency_key"),
                        rs.getString("request_fingerprint"),
                        rs.getTimestamp("created_at").toInstant()),
                key);
        return rows.stream().findFirst();
    }

    @Override
    public void save(IdempotencyRecord record) {
        try {
            jdbc.update("INSERT INTO core.idempotency_records (idempotency_key, request_fingerprint, created_at) "
                            + "VALUES (?, ?, ?)",
                    record.key(), record.requestFingerprint(),
                    Timestamp.from(record.createdAt() == null ? Instant.now() : record.createdAt()));
        } catch (DuplicateKeyException e) {
            // 并发同键插入：唯一约束兜底（谁先落库谁赢）；重放判定由 find() 语义承接
        }
    }

    /** 首次成功结果（重放返回用）。 */
    public void saveResponse(String key, String responseBody) {
        jdbc.update("UPDATE core.idempotency_records SET response_body = ? WHERE idempotency_key = ?",
                responseBody, key);
    }

    public Optional<String> findResponse(String key) {
        List<String> rows = jdbc.queryForList(
                "SELECT response_body FROM core.idempotency_records WHERE idempotency_key = ? "
                        + "AND response_body IS NOT NULL",
                String.class, key);
        return rows.stream().findFirst();
    }
}
