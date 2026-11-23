package com.company.retail.bureau.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 征信报送留痕 Mapper（integration.credit_bureau_reports；consent_id NOT NULL，INV-5）。
 */
public interface BureauReportMapper {

    /**
     * 插入 PENDING 留痕（source 显式声明：AUTO=自动通道 / MANUAL=人工通道，V15）。
     *
     * <p>ON CONFLICT DO NOTHING：唯一冲突（uk_report_loan_trigger）时不抛异常、受影响行数为 0，
     * 由仓储层判为「幂等冲突」返回 false——与原实现捕获 DuplicateKeyException 后返回 false 的
     * 语义一致（RF_CONFLICT_001）；非唯一约束的违例（如 NOT NULL）仍照旧抛错。</p>
     */
    @Insert("INSERT INTO integration.credit_bureau_reports "
            + "(loan_id, consent_id, bureau, trigger, status, source, trace_id) "
            + "VALUES (#{loanId}, #{consentId}, #{bureau}, #{trigger}, 'PENDING', #{source}, "
            + "#{traceId,jdbcType=VARCHAR}) ON CONFLICT DO NOTHING")
    int insertPending(@Param("loanId") long loanId,
                      @Param("consentId") long consentId,
                      @Param("bureau") String bureau,
                      @Param("trigger") String trigger,
                      @Param("source") String source,
                      @Param("traceId") String traceId);

    /** 推进报送状态（SENT/REJECTED）；sent_at 仅在 SENT 时落 now()。 */
    @Update("UPDATE integration.credit_bureau_reports SET status = #{status}, "
            + "payload_ref = #{payloadRef,jdbcType=VARCHAR}, "
            + "response_ref = COALESCE(#{responseRef,jdbcType=VARCHAR}, response_ref), "
            + "sent_at = CASE WHEN #{status} = 'SENT' THEN now() ELSE sent_at END, "
            + "trace_id = #{traceId,jdbcType=VARCHAR}, version = version + 1, updated_at = now() "
            + "WHERE report_id = #{reportId}")
    int markFinished(@Param("reportId") long reportId,
                     @Param("status") String status,
                     @Param("payloadRef") String payloadRef,
                     @Param("responseRef") String responseRef,
                     @Param("traceId") String traceId);

    /** 按 report_id 定位（uk_report_loan_trigger 唯一约束的读取侧）。 */
    @Select("SELECT report_id, loan_id, consent_id, bureau, trigger, status, source "
            + "FROM integration.credit_bureau_reports WHERE report_id = #{reportId}")
    List<Map<String, Object>> findById(@Param("reportId") long reportId);

    /** 按 (loan_id, trigger) 定位（uk_report_loan_trigger 唯一约束的读取侧）。 */
    @Select("SELECT report_id, loan_id, consent_id, bureau, trigger, status, source "
            + "FROM integration.credit_bureau_reports WHERE loan_id = #{loanId} AND trigger = #{trigger}")
    List<Map<String, Object>> findByLoanAndTrigger(@Param("loanId") long loanId,
                                                    @Param("trigger") String trigger);
}
