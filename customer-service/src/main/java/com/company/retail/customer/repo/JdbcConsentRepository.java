package com.company.retail.customer.repo;

import com.company.retail.customer.domain.Consent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * pii.consents（append-only）+ pii.consent_withdrawals 的 JDBC 仓储（R06 §4.1）。
 * 撤回不改写历史：consents.status 落 WITHDRAWN + withdrawals 另表留痕。
 */
@Repository
public class JdbcConsentRepository {

    private final JdbcTemplate jdbc;

    public JdbcConsentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long customerId, String purpose, String consentVersion, String channel,
                       Long evidenceAttachmentId, Instant expiresAt, String traceId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pii.consents (customer_id, purpose, consent_version, status, expires_at, "
                            + "channel, evidence_attachment_id, trace_id) "
                            + "VALUES (?, ?, ?, 'GRANTED', ?, ?, ?, ?)", new String[]{"consent_id"});
            ps.setLong(1, customerId);
            ps.setString(2, purpose);
            ps.setString(3, consentVersion == null ? "v1" : consentVersion);
            ps.setTimestamp(4, expiresAt == null ? null : Timestamp.from(expiresAt), null);
            ps.setString(5, channel);
            if (evidenceAttachmentId == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, evidenceAttachmentId);
            }
            ps.setString(7, traceId);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public Optional<Consent> findById(long consentId) {
        var rows = jdbc.queryForList(
                "SELECT consent_id, customer_id, purpose, consent_version, status, granted_at, expires_at "
                        + "FROM pii.consents WHERE consent_id = ?", consentId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toConsent(rows.get(0)));
    }

    /** 同意有效性查询（下游门禁数据面）：最新一条未过期 GRANTED；查不到 = 无效（fail-closed）。 */
    public Optional<Consent> findActiveByCustomerAndPurpose(long customerId, String purpose) {
        var rows = jdbc.queryForList("""
                SELECT consent_id, customer_id, purpose, consent_version, status, granted_at, expires_at
                FROM pii.consents
                WHERE customer_id = ? AND purpose = ? AND status = 'GRANTED'
                  AND (expires_at IS NULL OR expires_at > now())
                ORDER BY granted_at DESC LIMIT 1
                """, customerId, purpose);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toConsent(rows.get(0)));
    }

    public void markWithdrawn(long consentId, Instant withdrawnAt) {
        jdbc.update(
                "UPDATE pii.consents SET status = 'WITHDRAWN', updated_at = now(), version = version + 1 "
                        + "WHERE consent_id = ?", consentId);
    }

    public long insertWithdrawal(long consentId, Instant withdrawnAt, String reason, String traceId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pii.consent_withdrawals (consent_id, withdrawn_at, reason, processed_at, trace_id) "
                            + "VALUES (?, ?, ?, ?, ?)", new String[]{"withdrawal_id"});
            ps.setLong(1, consentId);
            ps.setTimestamp(2, Timestamp.from(withdrawnAt), null);
            ps.setString(3, reason);
            ps.setTimestamp(4, Timestamp.from(withdrawnAt), null);
            ps.setString(5, traceId);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    private Consent toConsent(Map<String, Object> row) {
        Object granted = row.get("granted_at");
        Object expires = row.get("expires_at");
        return new Consent(
                ((Number) row.get("consent_id")).longValue(),
                ((Number) row.get("customer_id")).longValue(),
                (String) row.get("purpose"),
                (String) row.get("consent_version"),
                Consent.Status.valueOf((String) row.get("status")),
                granted == null ? null : ((Timestamp) granted).toInstant(),
                expires == null ? null : ((Timestamp) expires).toInstant());
    }
}
