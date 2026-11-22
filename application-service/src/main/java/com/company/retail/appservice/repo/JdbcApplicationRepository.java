package com.company.retail.appservice.repo;

import com.company.retail.appservice.domain.Application;
import com.company.retail.appservice.domain.ApplicationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * core.applications + core.application_items 的 JDBC 仓储（R06 §4.2）。
 */
@Repository
public class JdbcApplicationRepository {

    private final JdbcTemplate jdbc;

    public JdbcApplicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 同客户同 VIN 在途（非终态）申请的最近提交时刻（去重窗口判定输入）。 */
    public Optional<Instant> findPriorDuplicateSubmittedAt(long customerId, String vin) {
        var rows = jdbc.queryForList("""
                SELECT MAX(a.submitted_at) AS submitted_at
                FROM core.applications a
                JOIN core.application_items i ON i.application_id = a.application_id
                WHERE a.customer_id = ? AND i.vin = ?
                  AND a.status NOT IN ('DECLINED', 'WITHDRAWN', 'CANCELLED', 'EXPIRED', 'CONTRACTED')
                """, customerId, vin);
        Object at = rows.isEmpty() ? null : rows.get(0).get("submitted_at");
        return at == null ? Optional.empty() : Optional.of(((Timestamp) at).toInstant());
    }

    /** 插入申请（SUBMITTED 起点）+ 车辆项，返回 applicationId。 */
    public long insert(Application agg, String applicationNo, Application.Item item,
                       String idempotencyKey, String requestFingerprint, String traceId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO core.applications (application_no, customer_id, dealer_partner_id, status,
                        transaction_scheme, submitted_at, channel, idempotency_key, request_fingerprint,
                        consent_ref, trace_id)
                    VALUES (?, ?, ?, 'SUBMITTED', ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"application_id"});
            ps.setString(1, applicationNo);
            ps.setLong(2, agg.getCustomerId());
            if (agg.getDealerPartnerId() == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, agg.getDealerPartnerId());
            }
            ps.setString(4, agg.getTransactionScheme());
            ps.setTimestamp(5, Timestamp.from(agg.getSubmittedAt()), null);
            ps.setString(6, agg.getChannel());
            ps.setString(7, idempotencyKey);
            ps.setString(8, requestFingerprint);
            if (agg.getConsentId() == null) {
                ps.setNull(9, java.sql.Types.BIGINT);
            } else {
                ps.setLong(9, agg.getConsentId());
            }
            ps.setString(10, traceId);
            return ps;
        }, kh);
        long applicationId = kh.getKey().longValue();
        jdbc.update("""
                INSERT INTO core.application_items (application_id, vin, model, cash_price, accessories_amount, trace_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, applicationId, item.vin(), item.model(), item.cashPrice(), item.accessoriesAmount(), traceId);
        return applicationId;
    }

    public Optional<Application> findById(long applicationId) {
        var rows = jdbc.queryForList("""
                SELECT application_id, application_no, customer_id, dealer_partner_id, status,
                       transaction_scheme, submitted_at, channel, idempotency_key, consent_ref
                FROM core.applications WHERE application_id = ?
                """, applicationId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toAggregate(rows.get(0)));
    }

    public Optional<Application> findByIdempotencyKey(String idempotencyKey) {
        var rows = jdbc.queryForList("""
                SELECT application_id, application_no, customer_id, dealer_partner_id, status,
                       transaction_scheme, submitted_at, channel, idempotency_key, consent_ref
                FROM core.applications WHERE idempotency_key = ?
                """, idempotencyKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toAggregate(rows.get(0)));
    }

    /** 状态回写（目标状态由聚合方法产出）。 */
    public void updateStatus(long applicationId, ApplicationStatus status) {
        jdbc.update("""
                UPDATE core.applications SET status = ?, updated_at = now(), version = version + 1
                WHERE application_id = ?
                """, status.name(), applicationId);
    }

    private Application toAggregate(Map<String, Object> row) {
        Object dealer = row.get("dealer_partner_id");
        Object submitted = row.get("submitted_at");
        // consent_ref：申请时声明的同意引用（挡板口径），无对应列时为空
        Object consentRef = row.getOrDefault("consent_ref", null);
        return Application.restore(
                ((Number) row.get("application_id")).longValue(),
                (String) row.get("application_no"),
                ((Number) row.get("customer_id")).longValue(),
                dealer == null ? null : ((Number) dealer).longValue(),
                ApplicationStatus.valueOf((String) row.get("status")),
                (String) row.get("transaction_scheme"),
                (String) row.get("channel"),
                submitted == null ? Instant.now() : ((Timestamp) submitted).toInstant(),
                consentRef == null ? null : Long.valueOf(String.valueOf(consentRef)));
    }
}
