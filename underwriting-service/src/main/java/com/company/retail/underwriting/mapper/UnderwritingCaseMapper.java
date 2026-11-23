package com.company.retail.underwriting.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 审查案件 Mapper（MyBatis 注解 SQL；表 {@code core.underwriting_cases}，V4 + V11 consent_id）。
 *
 * <p>查询统一返回 {@code Map<String,Object>}（键 = 列标签，小写下划线，不做驼峰转换），
 * 领域对象的组装在仓储层 {@code UnderwritingCaseRepository}。</p>
 *
 * <p>写入语义：主键由驱动回填到入参 Map 的 {@code caseId} 键；更新返回受影响行数
 * （乐观锁 version 判定依据，等价原 {@code jdbc.update} 返回值）。</p>
 */
public interface UnderwritingCaseMapper {

    /**
     * 插入新案件（PENDING）。
     *
     * <p>主键由驱动回填到 {@code params} 的 {@code caseId} 键（原实现用 {@code RETURNING case_id} 取值，
     * 语义等价）。唯一键 {@code uk_uwc_app} 冲突由驱动抛异常，仓储层转 RF_CONFLICT_001。</p>
     */
    @Insert("INSERT INTO core.underwriting_cases (application_id, consent_id, status, rule_version) "
            + "VALUES (#{applicationId}, #{consentId}, #{status}, #{ruleVersion,jdbcType=VARCHAR})")
    @Options(useGeneratedKeys = true, keyProperty = "caseId", keyColumn = "case_id")
    int insert(Map<String, Object> params);

    /** 按主键查询案件（返回行，由仓储层组装领域记录）。 */
    @Select("SELECT case_id, application_id, consent_id, status, outcome, decided_by, reviewed_by, "
            + "decided_at, rule_version, version FROM core.underwriting_cases WHERE case_id = #{caseId}")
    List<Map<String, Object>> findById(@Param("caseId") long caseId);

    /**
     * 状态变更 + 乐观锁 version（{@code WHERE case_id = ? AND version = ?}）。
     *
     * <p>返回受影响行数：0 = version 不匹配（并发修改），&gt;0 = 更新成功。
     * 状态字段只随聚合快照整体落库，不做单列改写（R00 §5）。</p>
     */
    @Update("UPDATE core.underwriting_cases SET status = #{status}, "
            + "outcome = #{outcome,jdbcType=VARCHAR}, "
            + "decided_by = #{decidedBy,jdbcType=VARCHAR}, "
            + "reviewed_by = #{reviewedBy,jdbcType=VARCHAR}, "
            + "decided_at = #{decidedAt,jdbcType=TIMESTAMP_WITH_TIMEZONE}, "
            + "rule_version = #{ruleVersion,jdbcType=VARCHAR}, "
            + "version = version + 1, updated_at = now() "
            + "WHERE case_id = #{caseId} AND version = #{version}")
    int update(@Param("status") String status,
               @Param("outcome") String outcome,
               @Param("decidedBy") String decidedBy,
               @Param("reviewedBy") String reviewedBy,
               @Param("decidedAt") java.time.OffsetDateTime decidedAt,
               @Param("ruleVersion") String ruleVersion,
               @Param("caseId") long caseId,
               @Param("version") int version);
}
