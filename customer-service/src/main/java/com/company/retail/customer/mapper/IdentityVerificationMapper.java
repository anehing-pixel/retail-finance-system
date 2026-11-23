package com.company.retail.customer.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 身份核验 Mapper（pii.identity_verifications + pii.identity_documents，R06 §4.1）。
 *
 * <p>核验材料只存附件引用与脱敏号，不存附件本体与证件号明文（ADR-RF-008 / 红线 B-1）。</p>
 *
 * <p>参数约定：自增主键的插入统一用 {@code Map<String,Object>} 单参数（全仓范式），
 * 主键由驱动回填到同名 key（未回填 = 未插入，按“未插入”处理）。</p>
 */
public interface IdentityVerificationMapper {

    /** 新建核验（status 固定 'PENDING'）。 */
    @Options(useGeneratedKeys = true, keyProperty = "verificationId", keyColumn = "verification_id")
    @Insert("INSERT INTO pii.identity_verifications (customer_id, status, method, trace_id) "
            + "VALUES (#{customerId}, 'PENDING', #{method}, #{traceId})")
    int insertVerification(Map<String, Object> params);

    /** 追加核验材料（附件只存引用 + 脱敏号）。 */
    @Options(useGeneratedKeys = true, keyProperty = "documentId", keyColumn = "document_id")
    @Insert("INSERT INTO pii.identity_documents (verification_id, document_type, attachment_id, "
            + "expiry_date, masked_number, trace_id) "
            + "VALUES (#{verificationId}, #{documentType}, #{attachmentId}, #{expiryDate}, "
            + "#{maskedNumber}, #{traceId})")
    int insertDocument(Map<String, Object> params);

    /** 核验主行（状态 + 客户）。 */
    @Select("SELECT verification_id, customer_id, status FROM pii.identity_verifications "
            + "WHERE verification_id = #{verificationId}")
    List<Map<String, Object>> findVerificationRows(@Param("verificationId") long verificationId);

    /** 核验材料清单（按 document_id 升序）。 */
    @Select("SELECT document_id, document_type, attachment_id, expiry_date, masked_number "
            + "FROM pii.identity_documents WHERE verification_id = #{verificationId} ORDER BY document_id")
    List<Map<String, Object>> findDocumentRows(@Param("verificationId") long verificationId);

    /** 状态回写（只由聚合方法决定目标状态，这里只落库）。 */
    @Update("UPDATE pii.identity_verifications "
            + "SET status = #{status}, verified_at = #{verifiedAt}, reviewer_id = #{reviewerId}, "
            + "result_snapshot = #{resultSnapshotJson}::jsonb, updated_at = now(), version = version + 1 "
            + "WHERE verification_id = #{verificationId}")
    int updateStatus(@Param("verificationId") long verificationId,
                     @Param("status") String status,
                     @Param("verifiedAt") Timestamp verifiedAt,
                     @Param("reviewerId") String reviewerId,
                     @Param("resultSnapshotJson") String resultSnapshotJson);
}
