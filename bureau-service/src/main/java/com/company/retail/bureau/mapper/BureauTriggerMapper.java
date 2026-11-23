package com.company.retail.bureau.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 报送触发登记 Mapper（core.bureau_report_triggers，V14）。
 *
 * <p>跨域只读投影：servicing 是本表真相源（触发登记），bureau 作为执行方
 * 只读 + 推进状态（R02 §6 真相源边界；跨域读已登记最终报告共享区建议）。</p>
 */
public interface BureauTriggerMapper {

    /** 按 (loan_id, trigger_type) 定位（uk_brt_loan_type 唯一约束的读取侧）。 */
    @Select("SELECT trigger_id, loan_id, dpd_stage, trigger_type, consent_id, target_biz_date, status "
            + "FROM core.bureau_report_triggers WHERE loan_id = #{loanId} AND trigger_type = #{triggerType}")
    List<Map<String, Object>> findByLoanAndType(@Param("loanId") long loanId,
                                                @Param("triggerType") String triggerType);

    /** 到期触发扫描（批处理用）：status=REGISTERED 且 target_biz_date &lt;= today，按 trigger_id 升序。 */
    @Select("SELECT trigger_id, loan_id, dpd_stage, trigger_type, consent_id, target_biz_date, status "
            + "FROM core.bureau_report_triggers WHERE status = 'REGISTERED' "
            + "AND target_biz_date <= #{today} ORDER BY trigger_id")
    List<Map<String, Object>> findDue(@Param("today") LocalDate today);

    /** 状态推进：REGISTERED → CONSUMED（已报送）/ BLOCKED（同意门禁拦截等）。 */
    @Update("UPDATE core.bureau_report_triggers SET status = #{status}, "
            + "version = version + 1, updated_at = now() WHERE trigger_id = #{triggerId}")
    int updateStatus(@Param("triggerId") long triggerId, @Param("status") String status);
}
