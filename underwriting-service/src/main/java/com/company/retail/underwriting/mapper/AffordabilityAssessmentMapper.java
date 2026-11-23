package com.company.retail.underwriting.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 支付能力评估快照 Mapper（MyBatis 注解 SQL；表 {@code core.affordability_assessments}）。
 *
 * <p>快照 append-only 不重算历史（R06 {@code uk_aff_case_version}）：唯一键冲突由驱动抛异常，
 * 仓储层转 RF_CONFLICT_001。</p>
 */
public interface AffordabilityAssessmentMapper {

    /**
     * 追加评估快照。
     *
     * <p>{@code input_snapshot} 走 PostgreSQL 的 {@code ::jsonb} 转型（口径与原 JDBC 写法一致，
     * 快照只存评估输入，收入为加密引用码、无 PII 明文，R06 §4.2）。</p>
     */
    @Insert("INSERT INTO core.affordability_assessments (case_id, annual_income_enc_ref, "
            + "existing_debt_total, threshold_amount, result, policy_version, input_snapshot) "
            + "VALUES (#{caseId}, #{annualIncomeEncRef,jdbcType=VARCHAR}, "
            + "#{existingDebtTotal}, #{thresholdAmount}, #{result}, #{policyVersion}, "
            + "#{inputSnapshot}::jsonb)")
    int insert(Map<String, Object> params);

    /** 查某案件最新一条快照（按 assessment_id 倒序取 1 条）。 */
    @Select("SELECT result, policy_version FROM core.affordability_assessments "
            + "WHERE case_id = #{caseId} ORDER BY assessment_id DESC LIMIT 1")
    List<Map<String, Object>> findLatest(@Param("caseId") long caseId);
}
