package com.company.retail.titlerelease.persistence;

import com.company.retail.titlerelease.application.TitleRepositoryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * {@code core.title_retentions} 持久化（V8 表；权威写者 BC-8 title-release-service，P-5）。
 * uk_title_loan：一债权一条保留记录。DOCUMENTS_ISSUED 不写本表状态（两态分离）。
 *
 * <p>插入用 {@code ON CONFLICT (loan_id) DO NOTHING RETURNING title_id}：并发写入
 * （操作员主动登记 vs LOAN_SETTLED 兜底建单）时<b>不抛唯一键异常</b>——冲突不插入、返回 0，
 * 由调用方幂等处理。顺带避免 PostgreSQL「唯一键错误即整笔事务 aborted」的坑。</p>
 */
@Repository
public class JdbcTitleRepository implements TitleRepositoryPort {

    private static final RowMapper<TitleRow> MAPPER = (rs, i) -> new TitleRow(
            rs.getLong("title_id"),
            rs.getLong("loan_id"),
            rs.getString("vin"),
            rs.getString("status"),
            rs.getString("registration_mode"),
            rs.getTimestamp("registered_at"),
            rs.getTimestamp("released_at"),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public JdbcTitleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入保留记录；uk_title_loan 冲突（并发被抢先）返回 0，不抛异常。 */
    @Override
    public long insert(long loanId, String vin, String status, String registrationMode,
                       Timestamp registeredAt, String traceId) {
        List<Long> ids = jdbc.query(
                "INSERT INTO core.title_retentions (loan_id, vin, status, registration_mode, "
                        + "registered_at, trace_id) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (loan_id) DO NOTHING RETURNING title_id",
                (RowMapper<Long>) (rs, i) -> rs.getLong("title_id"),
                loanId, vin, status, registrationMode, registeredAt, traceId);
        return ids.isEmpty() ? 0 : ids.get(0);
    }

    @Override
    public Optional<TitleRow> findByLoanId(long loanId) {
        List<TitleRow> rows = jdbc.query(
                "SELECT * FROM core.title_retentions WHERE loan_id = ?", MAPPER, loanId);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<TitleRow> findById(long titleId) {
        List<TitleRow> rows = jdbc.query(
                "SELECT * FROM core.title_retentions WHERE title_id = ?", MAPPER, titleId);
        return rows.stream().findFirst();
    }

    /** 状态迁移（RELEASE_REGISTERED / CLOSED；乐观锁兜底）。 */
    @Override
    public int updateStatus(long titleId, String status, Timestamp releasedAt, int version) {
        return jdbc.update(
                "UPDATE core.title_retentions SET status = ?, released_at = COALESCE(?, released_at), "
                        + "version = version + 1, updated_at = now() WHERE title_id = ? AND version = ?",
                status, releasedAt, titleId, version);
    }
}
