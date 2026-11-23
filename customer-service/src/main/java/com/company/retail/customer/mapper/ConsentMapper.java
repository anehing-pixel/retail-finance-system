package com.company.retail.customer.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 同意登记 Mapper（pii.consents + pii.consent_withdrawals，R06 §4.1）。
 *
 * <p>append-only：撤回不改写历史，consents.status 落 WITHDRAWN + withdrawals 另表留痕（INV-5）。</p>
 *
 * <p>参数约定：自增主键的插入统一用 {@code Map<String,Object>} 单参数（全仓范式），
 * 主键由驱动回填到同名 key（未回填 = 未插入，按“未插入”处理）。</p>
 */
public interface ConsentMapper {

    /** 取得同意（status 固定 'GRANTED'）。 */
    @Options(useGeneratedKeys = true, keyProperty = "consentId", keyColumn = "consent_id")
    @Insert("INSERT INTO pii.consents (customer_id, purpose, consent_version, status, expires_at, "
            + "channel, evidence_attachment_id, trace_id) "
            + "VALUES (#{customerId}, #{purpose}, #{consentVersion}, 'GRANTED', #{expiresAt}, "
            + "#{channel}, #{evidenceAttachmentId}, #{traceId})")
    int insertConsent(Map<String, Object> params);

    /** 按主键查同意。 */
    @Select("SELECT consent_id, customer_id, purpose, consent_version, status, granted_at, expires_at "
            + "FROM pii.consents WHERE consent_id = #{consentId}")
    List<Map<String, Object>> findConsentRows(@Param("consentId") long consentId);

    /** 同意有效性查询：最新一条未过期 GRANTED；查不到 = 无效（fail-closed）。 */
    @Select("SELECT consent_id, customer_id, purpose, consent_version, status, granted_at, expires_at "
            + "FROM pii.consents "
            + "WHERE customer_id = #{customerId} AND purpose = #{purpose} AND status = 'GRANTED' "
            + "AND (expires_at IS NULL OR expires_at > now()) "
            + "ORDER BY granted_at DESC LIMIT 1")
    List<Map<String, Object>> findActiveRows(@Param("customerId") long customerId,
                                             @Param("purpose") String purpose);

    /** 语义撤回：只改状态（撤回时刻与原因落在 pii.consent_withdrawals，历史不改写）。 */
    @Update("UPDATE pii.consents SET status = 'WITHDRAWN', updated_at = now(), version = version + 1 "
            + "WHERE consent_id = #{consentId}")
    int markWithdrawn(@Param("consentId") long consentId);

    /** 撤回留痕（consent_id 唯一，一同意一撤回）。 */
    @Options(useGeneratedKeys = true, keyProperty = "withdrawalId", keyColumn = "withdrawal_id")
    @Insert("INSERT INTO pii.consent_withdrawals (consent_id, withdrawn_at, reason, processed_at, trace_id) "
            + "VALUES (#{consentId}, #{withdrawnAt}, #{reason}, #{processedAt}, #{traceId})")
    int insertWithdrawal(Map<String, Object> params);
}
