package com.company.retail.servicing.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 征信报送触发登记 MyBatis Mapper（表 core.bureau_report_triggers，V14；uk_brt_loan_type 幂等）。
 *
 * <p>servicing 只做触发登记与状态跟踪；integration.credit_bureau_reports 的写者是
 * bureau-service（R02 §6 真相源边界）。</p>
 */
public interface BureauReportTriggerMapper {

    /**
     * 登记触发（uk_brt_loan_type 一债权一触发类型）。
     *
     * <p>用 {@code ON CONFLICT (loan_id, trigger_type) DO NOTHING} 而非「捕获 DuplicateKeyException」：
     * PostgreSQL 事务内一旦触发唯一键错误，整笔事务即被置为 aborted，无法继续读库
     * （与 title-release-service 的 uk_title_loan 同款口径）。冲突时不插入，返回 0 行。</p>
     */
    @Insert("INSERT INTO core.bureau_report_triggers "
            + "(loan_id, dpd_stage, trigger_type, consent_id, target_biz_date, trace_id) "
            + "VALUES (#{loanId}, #{dpdStage}, #{triggerType}, #{consentId}, #{targetBizDate}, #{traceId}) "
            + "ON CONFLICT (loan_id, trigger_type) DO NOTHING")
    int insert(@Param("loanId") long loanId,
               @Param("dpdStage") String dpdStage,
               @Param("triggerType") String triggerType,
               @Param("consentId") long consentId,
               @Param("targetBizDate") LocalDate targetBizDate,
               @Param("traceId") String traceId);

    @Select("SELECT trigger_id, loan_id, dpd_stage, trigger_type, consent_id, target_biz_date, status "
            + "FROM core.bureau_report_triggers WHERE trigger_id = #{triggerId}")
    List<Map<String, Object>> findById(@Param("triggerId") long triggerId);

    @Select("SELECT trigger_id, loan_id, dpd_stage, trigger_type, consent_id, target_biz_date, status "
            + "FROM core.bureau_report_triggers WHERE loan_id = #{loanId} AND trigger_type = #{triggerType}")
    List<Map<String, Object>> findByLoanAndType(@Param("loanId") long loanId,
                                                @Param("triggerType") String triggerType);

    @Select("<script>"
            + "SELECT trigger_id, loan_id, dpd_stage, trigger_type, consent_id, target_biz_date, status "
            + "FROM core.bureau_report_triggers"
            + "<if test=\"statusOrNull != null and statusOrNull.trim().length() > 0\">"
            + " WHERE status = #{statusOrNull}"
            + "</if>"
            + " ORDER BY trigger_id DESC LIMIT 200"
            + "</script>")
    List<Map<String, Object>> list(@Param("statusOrNull") String statusOrNull);

    @Update("UPDATE core.bureau_report_triggers SET status = #{status}, trace_id = #{traceId}, "
            + "updated_at = now(), version = version + 1 WHERE trigger_id = #{triggerId}")
    int updateStatus(@Param("triggerId") long triggerId,
                     @Param("status") String status,
                     @Param("traceId") String traceId);
}
