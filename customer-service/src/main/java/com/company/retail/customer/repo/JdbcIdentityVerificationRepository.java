package com.company.retail.customer.repo;

import com.company.retail.customer.domain.IdentityVerification;
import com.company.retail.customer.domain.IdentityVerificationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * pii.identity_verifications + pii.identity_documents 的 JDBC 仓储（R06 §4.1）。
 */
@Repository
public class JdbcIdentityVerificationRepository {

    private final JdbcTemplate jdbc;

    public JdbcIdentityVerificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建核验（C-07：method 固定 MANUAL，eKYC 预留）。 */
    public long insert(long customerId, String method, String traceId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pii.identity_verifications (customer_id, status, method, trace_id) "
                            + "VALUES (?, 'PENDING', ?, ?)", new String[]{"verification_id"});
            ps.setLong(1, customerId);
            ps.setString(2, method == null ? "MANUAL" : method);
            ps.setString(3, traceId);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public long insertDocument(long verificationId, String documentType, Long attachmentId,
                               java.time.LocalDate expiryDate, String maskedNumber, String traceId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pii.identity_documents (verification_id, document_type, attachment_id, "
                            + "expiry_date, masked_number, trace_id) VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"document_id"});
            ps.setLong(1, verificationId);
            ps.setString(2, documentType);
            if (attachmentId == null) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, attachmentId);
            }
            if (expiryDate == null) {
                ps.setNull(4, Types.DATE);
            } else {
                ps.setObject(4, java.sql.Date.valueOf(expiryDate));
            }
            ps.setString(5, maskedNumber);
            ps.setString(6, traceId);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    /** 聚合装载：状态 + 材料清单。 */
    public IdentityVerification load(long verificationId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT verification_id, customer_id, status FROM pii.identity_verifications "
                        + "WHERE verification_id = ?", verificationId);
        IdentityVerification agg = new IdentityVerification(
                ((Number) row.get("verification_id")).longValue(),
                ((Number) row.get("customer_id")).longValue(),
                IdentityVerificationStatus.valueOf((String) row.get("status")));
        List<Map<String, Object>> docs = jdbc.queryForList(
                "SELECT document_id, document_type, attachment_id, expiry_date, masked_number "
                        + "FROM pii.identity_documents WHERE verification_id = ? ORDER BY document_id",
                verificationId);
        for (Map<String, Object> d : docs) {
            Object expiry = d.get("expiry_date");
            agg.restoreDocument(new IdentityVerification.Document(
                    ((Number) d.get("document_id")).longValue(),
                    (String) d.get("document_type"),
                    d.get("attachment_id") == null ? null : ((Number) d.get("attachment_id")).longValue(),
                    expiry == null ? null : (expiry instanceof java.sql.Date sd
                            ? sd.toLocalDate() : ((java.time.LocalDate) expiry)),
                    (String) d.get("masked_number")));
        }
        return agg;
    }

    /** 状态回写（只由聚合方法决定目标状态，这里只落库）。 */
    public void updateStatus(long verificationId, IdentityVerificationStatus status,
                             Instant verifiedAt, String reviewerId, String resultSnapshotJson) {
        jdbc.update("""
                UPDATE pii.identity_verifications
                SET status = ?, verified_at = ?, reviewer_id = ?, result_snapshot = ?::jsonb,
                    updated_at = now(), version = version + 1
                WHERE verification_id = ?
                """, status.name(),
                verifiedAt == null ? null : java.sql.Timestamp.from(verifiedAt),
                reviewerId, resultSnapshotJson, verificationId);
    }
}
