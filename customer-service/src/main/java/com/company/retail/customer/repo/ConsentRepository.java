package com.company.retail.customer.repo;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.customer.domain.Consent;
import com.company.retail.customer.mapper.ConsentMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * pii.consents（append-only）+ pii.consent_withdrawals 的 MyBatis 仓储（R06 §4.1）。
 * 撤回不改写历史：consents.status 落 WITHDRAWN + withdrawals 另表留痕。
 */
@Repository
public class ConsentRepository {

    private final ConsentMapper mapper;

    public ConsentRepository(ConsentMapper mapper) {
        this.mapper = mapper;
    }

    public long insert(long customerId, String purpose, String consentVersion, String channel,
                       Long evidenceAttachmentId, Instant expiresAt, String traceId) {
        Map<String, Object> params = new HashMap<>();
        params.put("customerId", customerId);
        params.put("purpose", purpose);
        params.put("consentVersion", consentVersion == null ? "v1" : consentVersion);
        params.put("channel", channel);
        params.put("evidenceAttachmentId", evidenceAttachmentId);
        params.put("expiresAt", expiresAt == null ? null : Timestamp.from(expiresAt));
        params.put("traceId", traceId);
        mapper.insertConsent(params);
        // 自增主键由驱动回填到入参 Map（未回填 = 未插入，与原 GeneratedKeyHolder 取值同口径）
        return ((Number) params.get("consentId")).longValue();
    }

    public Optional<Consent> findById(long consentId) {
        List<Map<String, Object>> rows = mapper.findConsentRows(consentId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toConsent(rows.get(0)));
    }

    /** 同意有效性查询（下游门禁数据面）：最新一条未过期 GRANTED；查不到 = 无效（fail-closed）。 */
    public Optional<Consent> findActiveByCustomerAndPurpose(long customerId, String purpose) {
        List<Map<String, Object>> rows = mapper.findActiveRows(customerId, purpose);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toConsent(rows.get(0)));
    }

    /** 语义撤回：只改状态（撤回时刻与原因落在 pii.consent_withdrawals，与原实现同口径）。 */
    public void markWithdrawn(long consentId, Instant withdrawnAt) {
        mapper.markWithdrawn(consentId);
    }

    public long insertWithdrawal(long consentId, Instant withdrawnAt, String reason, String traceId) {
        Map<String, Object> params = new HashMap<>();
        params.put("consentId", consentId);
        params.put("withdrawnAt", Timestamp.from(withdrawnAt));
        params.put("reason", reason);
        params.put("processedAt", Timestamp.from(withdrawnAt));
        params.put("traceId", traceId);
        mapper.insertWithdrawal(params);
        // 自增主键由驱动回填到入参 Map（未回填 = 未插入，与原 GeneratedKeyHolder 取值同口径）
        return ((Number) params.get("withdrawalId")).longValue();
    }

    private Consent toConsent(Map<String, Object> row) {
        return new Consent(
                RowValues.asLong(row, "consent_id"),
                RowValues.asLong(row, "customer_id"),
                RowValues.asString(row, "purpose"),
                RowValues.asString(row, "consent_version"),
                Consent.Status.valueOf(RowValues.asString(row, "status")),
                RowValues.asInstant(row, "granted_at"),
                RowValues.asInstant(row, "expires_at"));
    }
}
