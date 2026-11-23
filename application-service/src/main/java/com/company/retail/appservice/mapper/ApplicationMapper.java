package com.company.retail.appservice.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 申请域 MyBatis Mapper（core.applications + core.application_items，R06 §4.2）。
 *
 * <p>SQL 与改造前的 JdbcTemplate 版逐字一致（仅占位符由 ? 改为 MyBatis 的 #{name}），
 * 语义不变：去重窗口定位、受理写入 + 生成键回填、车辆项写入、状态回写。</p>
 *
 * <p><b>生成键口径</b>：受理插入用 {@code useGeneratedKeys} 回填 {@code application_id}，
 * 写入后由仓储从入参 Map 的 {@code applicationId} 键读取；冲突/未插入时该键为 null
 * （等价原 RETURNING 返回空集），仓储按「未插入」处理。</p>
 */
public interface ApplicationMapper {

    /** 同客户同 VIN 在途（非终态）申请的最近提交时刻（去重窗口判定输入）。 */
    @Select("SELECT MAX(a.submitted_at) AS submitted_at "
            + "FROM core.applications a "
            + "JOIN core.application_items i ON i.application_id = a.application_id "
            + "WHERE a.customer_id = #{customerId} AND i.vin = #{vin} "
            + "AND a.status NOT IN ('DECLINED', 'WITHDRAWN', 'CANCELLED', 'EXPIRED', 'CONTRACTED')")
    List<Map<String, Object>> findPriorDuplicateSubmittedAt(@Param("customerId") long customerId,
                                                            @Param("vin") String vin);

    /**
     * 插入申请（SUBMITTED 起点），回填生成键 application_id。
     *
     * <p>只接受一个可变参数对象（Map）：键名与 SQL 占位符一致，
     * 插入成功后生成键以 {@code applicationId} 为键写回该 Map。</p>
     */
    @Insert("INSERT INTO core.applications (application_no, customer_id, dealer_partner_id, status, "
            + "transaction_scheme, submitted_at, channel, idempotency_key, request_fingerprint, "
            + "consent_ref, trace_id) "
            + "VALUES (#{applicationNo}, #{customerId}, #{dealerPartnerId}, 'SUBMITTED', "
            + "#{transactionScheme}, #{submittedAt}, #{channel}, #{idempotencyKey}, "
            + "#{requestFingerprint}, #{consentRef}, #{traceId})")
    @Options(useGeneratedKeys = true, keyProperty = "applicationId", keyColumn = "application_id")
    int insertApplication(Map<String, Object> params);

    /** 插入车辆项（core.application_items；item_id 用不到，不取生成键）。 */
    @Insert("INSERT INTO core.application_items (application_id, vin, model, cash_price, "
            + "accessories_amount, trace_id) "
            + "VALUES (#{applicationId}, #{vin}, #{model}, #{cashPrice}, #{accessoriesAmount}, #{traceId})")
    int insertItem(@Param("applicationId") long applicationId,
                   @Param("vin") String vin,
                   @Param("model") String model,
                   @Param("cashPrice") long cashPrice,
                   @Param("accessoriesAmount") long accessoriesAmount,
                   @Param("traceId") String traceId);

    /** 按主键读申请行。 */
    @Select("SELECT application_id, application_no, customer_id, dealer_partner_id, status, "
            + "transaction_scheme, submitted_at, channel, idempotency_key, consent_ref "
            + "FROM core.applications WHERE application_id = #{applicationId}")
    List<Map<String, Object>> findById(@Param("applicationId") long applicationId);

    /** 按幂等键读申请行（重放定位）。 */
    @Select("SELECT application_id, application_no, customer_id, dealer_partner_id, status, "
            + "transaction_scheme, submitted_at, channel, idempotency_key, consent_ref "
            + "FROM core.applications WHERE idempotency_key = #{idempotencyKey}")
    List<Map<String, Object>> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /** 状态回写（目标状态由聚合方法产出；version 递增，updated_at 取库端 now()）。 */
    @Update("UPDATE core.applications SET status = #{status}, updated_at = now(), version = version + 1 "
            + "WHERE application_id = #{applicationId}")
    int updateStatus(@Param("applicationId") long applicationId, @Param("status") String status);
}
