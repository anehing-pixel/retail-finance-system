package com.company.retail.bureau.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 征信查询留痕 Mapper（core.credit_inquiries；consent_id NOT NULL 由 DB 兜底，INV-5）。
 *
 * <p>注解 SQL（不用 XML）：查询统一返回 {@code Map<String,Object>}，键 = 原 SQL 列名
 * （小写下划线，不做驼峰转换）；行组装在仓储层（RowValues）。</p>
 */
public interface CreditInquiryMapper {

    /** 插入 REQUESTED 留痕；自增主键 inquiry_id 由驱动回填到 {@code inquiryId}。 */
    @Insert("INSERT INTO core.credit_inquiries (case_id, consent_id, bureau, purpose, status, trace_id) "
            + "VALUES (#{caseId}, #{consentId}, #{bureau}, #{purpose}, 'REQUESTED', "
            + "#{traceId,jdbcType=VARCHAR})")
    @Options(useGeneratedKeys = true, keyProperty = "inquiryId", keyColumn = "inquiry_id")
    int insert(InquiryInsert param);

    /** 推进查询状态（COMPLETED/FAILED）；返回受影响行数（&gt;0 表示命中）。 */
    @Update("UPDATE core.credit_inquiries SET status = #{status}, responded_at = now(), "
            + "result_snapshot_ref = #{resultSnapshotRef,jdbcType=VARCHAR}, cost_amount = #{costAmount}, "
            + "trace_id = #{traceId,jdbcType=VARCHAR}, version = version + 1, updated_at = now() "
            + "WHERE inquiry_id = #{inquiryId}")
    int markFinished(@Param("inquiryId") long inquiryId,
                     @Param("status") String status,
                     @Param("resultSnapshotRef") String resultSnapshotRef,
                     @Param("costAmount") long costAmount,
                     @Param("traceId") String traceId);

    /** 查询留痕行（行组装在仓储层）。 */
    @Select("SELECT inquiry_id, case_id, consent_id, bureau, purpose, status "
            + "FROM core.credit_inquiries WHERE inquiry_id = #{inquiryId}")
    List<Map<String, Object>> findById(@Param("inquiryId") long inquiryId);

    /** 按案件统计失败次数（3 次重试后转人工，G4/X-2）。 */
    @Select("SELECT COUNT(*) FROM core.credit_inquiries "
            + "WHERE case_id = #{caseId} AND bureau = #{bureau} AND status = 'FAILED'")
    Integer countFailed(@Param("caseId") long caseId, @Param("bureau") String bureau);

    /**
     * 插入参数对象（可变参数对象；自增主键由驱动回填，未回填时保持 null）。
     */
    class InquiryInsert {

        private long caseId;
        private long consentId;
        private String bureau;
        private String purpose;
        private String traceId;
        private Long inquiryId;

        public InquiryInsert() {
        }

        public InquiryInsert(long caseId, long consentId, String bureau, String purpose, String traceId) {
            this.caseId = caseId;
            this.consentId = consentId;
            this.bureau = bureau;
            this.purpose = purpose;
            this.traceId = traceId;
        }

        public long getCaseId() {
            return caseId;
        }

        public void setCaseId(long caseId) {
            this.caseId = caseId;
        }

        public long getConsentId() {
            return consentId;
        }

        public void setConsentId(long consentId) {
            this.consentId = consentId;
        }

        public String getBureau() {
            return bureau;
        }

        public void setBureau(String bureau) {
            this.bureau = bureau;
        }

        public String getPurpose() {
            return purpose;
        }

        public void setPurpose(String purpose) {
            this.purpose = purpose;
        }

        public String getTraceId() {
            return traceId;
        }

        public void setTraceId(String traceId) {
            this.traceId = traceId;
        }

        public Long getInquiryId() {
            return inquiryId;
        }

        public void setInquiryId(Long inquiryId) {
            this.inquiryId = inquiryId;
        }
    }
}
