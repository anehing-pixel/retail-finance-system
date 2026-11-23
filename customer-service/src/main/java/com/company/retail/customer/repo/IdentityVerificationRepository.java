package com.company.retail.customer.repo;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.customer.domain.IdentityVerification;
import com.company.retail.customer.domain.IdentityVerificationStatus;
import com.company.retail.customer.mapper.IdentityVerificationMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * pii.identity_verifications + pii.identity_documents 的 MyBatis 仓储（R06 §4.1）。
 */
@Repository
public class IdentityVerificationRepository {

    private final IdentityVerificationMapper mapper;

    public IdentityVerificationRepository(IdentityVerificationMapper mapper) {
        this.mapper = mapper;
    }

    /** 新建核验（C-07：method 固定 MANUAL，eKYC 预留）。 */
    public long insert(long customerId, String method, String traceId) {
        Map<String, Object> params = new HashMap<>();
        params.put("customerId", customerId);
        params.put("method", method == null ? "MANUAL" : method);
        params.put("traceId", traceId);
        mapper.insertVerification(params);
        // 自增主键由驱动回填到入参 Map（未回填 = 未插入，与原 GeneratedKeyHolder 取值同口径）
        return ((Number) params.get("verificationId")).longValue();
    }

    public long insertDocument(long verificationId, String documentType, Long attachmentId,
                               LocalDate expiryDate, String maskedNumber, String traceId) {
        Map<String, Object> params = new HashMap<>();
        params.put("verificationId", verificationId);
        params.put("documentType", documentType);
        params.put("attachmentId", attachmentId);
        params.put("expiryDate", expiryDate == null ? null : java.sql.Date.valueOf(expiryDate));
        params.put("maskedNumber", maskedNumber);
        params.put("traceId", traceId);
        mapper.insertDocument(params);
        // 自增主键由驱动回填到入参 Map（未回填 = 未插入，与原 GeneratedKeyHolder 取值同口径）
        return ((Number) params.get("documentId")).longValue();
    }

    /** 聚合装载：状态 + 材料清单。 */
    public IdentityVerification load(long verificationId) {
        List<Map<String, Object>> rows = mapper.findVerificationRows(verificationId);
        if (rows.isEmpty()) {
            // 与原 JdbcTemplate queryForMap 口径一致：查不到即抛空结果异常，不返回 null 聚合
            throw new EmptyResultDataAccessException(1);
        }
        Map<String, Object> row = rows.get(0);
        IdentityVerification agg = new IdentityVerification(
                RowValues.asLong(row, "verification_id"),
                RowValues.asLong(row, "customer_id"),
                IdentityVerificationStatus.valueOf(RowValues.asString(row, "status")));
        List<Map<String, Object>> docs = mapper.findDocumentRows(verificationId);
        for (Map<String, Object> d : docs) {
            agg.restoreDocument(new IdentityVerification.Document(
                    RowValues.asLong(d, "document_id"),
                    RowValues.asString(d, "document_type"),
                    RowValues.asLongOrNull(d, "attachment_id"),
                    RowValues.asLocalDate(d, "expiry_date"),
                    RowValues.asString(d, "masked_number")));
        }
        return agg;
    }

    /** 状态回写（只由聚合方法决定目标状态，这里只落库）。 */
    public void updateStatus(long verificationId, IdentityVerificationStatus status,
                             Instant verifiedAt, String reviewerId, String resultSnapshotJson) {
        mapper.updateStatus(verificationId, status.name(),
                verifiedAt == null ? null : Timestamp.from(verifiedAt),
                reviewerId, resultSnapshotJson);
    }
}
