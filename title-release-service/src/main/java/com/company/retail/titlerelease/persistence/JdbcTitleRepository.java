package com.company.retail.titlerelease.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * {@code core.title_retentions} 持久化（V8 表；权威写者 BC-8 title-release-service，P-5）。
 * uk_title_loan：一债权一条保留记录。DOCUMENTS_ISSUED 不写本表状态（两态分离）。
 */
@Repository
public class JdbcTitleRepository {

    public record TitleRow(long titleId, long loanId, String vin, String status,
                           String registrationMode, Timestamp registeredAt, Timestamp releasedAt,
                           int version) {
    }

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

    public long insert(long loanId, String vin, String status, String registrationMode,
                       Timestamp registeredAt, String traceId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO core.title_retentions (loan_id, vin, status, registration_mode, "
                            + "registered_at, trace_id) VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"title_id"});
            ps.setLong(1, loanId);
            ps.setString(2, vin);
            ps.setString(3, status);
            ps.setString(4, registrationMode);
            ps.setTimestamp(5, registeredAt);
            ps.setString(6, traceId);
            return ps;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    public Optional<TitleRow> findByLoanId(long loanId) {
        List<TitleRow> rows = jdbc.query(
                "SELECT * FROM core.title_retentions WHERE loan_id = ?", MAPPER, loanId);
        return rows.stream().findFirst();
    }

    public Optional<TitleRow> findById(long titleId) {
        List<TitleRow> rows = jdbc.query(
                "SELECT * FROM core.title_retentions WHERE title_id = ?", MAPPER, titleId);
        return rows.stream().findFirst();
    }

    /** 状态迁移（RELEASE_REGISTERED / CLOSED；乐观锁兜底）。 */
    public int updateStatus(long titleId, String status, Timestamp releasedAt, int version) {
        return jdbc.update(
                "UPDATE core.title_retentions SET status = ?, released_at = COALESCE(?, released_at), "
                        + "version = version + 1, updated_at = now() WHERE title_id = ? AND version = ?",
                status, releasedAt, titleId, version);
    }
}
