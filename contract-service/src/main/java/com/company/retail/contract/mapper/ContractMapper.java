package com.company.retail.contract.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 合同 Mapper（MyBatis 注解 SQL；表 {@code core.contracts}）。
 *
 * <p>查询统一返回 {@code Map<String,Object>}（键 = 列标签，小写下划线，不做驼峰转换），
 * 领域对象的组装在仓储层 {@code ContractRepository}。</p>
 *
 * <p>写入语义：主键由驱动回填到入参 Map 的 {@code contractId} 键；更新返回受影响行数
 * （乐观锁 version 判定依据，等价原 {@code jdbc.update} 返回值）。</p>
 */
public interface ContractMapper {

    /**
     * 插入合同（DRAFT）。
     *
     * <p>{@code terms_snapshot} 走 PostgreSQL 的 {@code ::jsonb} 转型（口径与原 JDBC 写法一致）。
     * 主键由驱动回填到 {@code params} 的 {@code contractId} 键（原实现用 GeneratedKeyHolder 取值，
     * 语义等价）。</p>
     */
    @Insert("INSERT INTO core.contracts (contract_no, application_id, customer_id, transaction_scheme, "
            + "status, signed_at, effective_at, cooling_off_until, terms_snapshot, trace_id) "
            + "VALUES (#{contractNo}, #{applicationId}, #{customerId}, #{transactionScheme}, "
            + "#{status}, #{signedAt,jdbcType=TIMESTAMP}, #{effectiveAt,jdbcType=TIMESTAMP}, "
            + "#{coolingOffUntil,jdbcType=DATE}, #{termsSnapshot}::jsonb, #{traceId,jdbcType=VARCHAR})")
    @Options(useGeneratedKeys = true, keyProperty = "contractId", keyColumn = "contract_id")
    int insert(Map<String, Object> params);

    /**
     * 状态推进 + 乐观锁 version（并发口径 R00 §5；行锁由 UPDATE ... WHERE version 保证）。
     *
     * <p>返回受影响行数：0 = version 不匹配（并发冲突），&gt;0 = 更新成功。
     * 状态变更只经聚合方法落库（R00 §5）。</p>
     */
    @Update("UPDATE core.contracts SET status = #{status}, "
            + "signed_at = #{signedAt,jdbcType=TIMESTAMP}, "
            + "effective_at = #{effectiveAt,jdbcType=TIMESTAMP}, "
            + "cooling_off_until = #{coolingOffUntil,jdbcType=DATE}, "
            + "terms_snapshot = #{termsSnapshot}::jsonb, version = version + 1, updated_at = now() "
            + "WHERE contract_id = #{contractId} AND version = #{version}")
    int updateAfterSigning(@Param("status") String status,
                           @Param("signedAt") java.sql.Timestamp signedAt,
                           @Param("effectiveAt") java.sql.Timestamp effectiveAt,
                           @Param("coolingOffUntil") java.sql.Date coolingOffUntil,
                           @Param("termsSnapshot") String termsSnapshot,
                           @Param("contractId") long contractId,
                           @Param("version") int version);

    /** 仅状态推进（cancel / terminate / 交付留痕后的 DOCUMENTS_DELIVERED）。 */
    @Update("UPDATE core.contracts SET status = #{status}, "
            + "signed_at = #{signedAt,jdbcType=TIMESTAMP}, "
            + "effective_at = #{effectiveAt,jdbcType=TIMESTAMP}, "
            + "cooling_off_until = #{coolingOffUntil,jdbcType=DATE}, "
            + "version = version + 1, updated_at = now() "
            + "WHERE contract_id = #{contractId} AND version = #{version}")
    int updateStatus(@Param("status") String status,
                     @Param("signedAt") java.sql.Timestamp signedAt,
                     @Param("effectiveAt") java.sql.Timestamp effectiveAt,
                     @Param("coolingOffUntil") java.sql.Date coolingOffUntil,
                     @Param("contractId") long contractId,
                     @Param("version") int version);

    /** 按主键查询合同（返回行，由仓储层组装领域记录）。 */
    @Select("SELECT contract_id, application_id, customer_id, transaction_scheme, status, signed_at, "
            + "effective_at, cooling_off_until, version FROM core.contracts WHERE contract_id = #{contractId}")
    List<Map<String, Object>> findById(@Param("contractId") long contractId);

    /** 按申请号查询合同（uk_contract_app：一个申请一份合同）。 */
    @Select("SELECT contract_id, application_id, customer_id, transaction_scheme, status, signed_at, "
            + "effective_at, cooling_off_until, version FROM core.contracts "
            + "WHERE application_id = #{applicationId}")
    List<Map<String, Object>> findByApplicationId(@Param("applicationId") long applicationId);
}
