package com.company.retail.bureau.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 征信异议工单 Mapper（integration.bureau_disputes；SLA 30 日 due_at，C-05）。
 */
public interface BureauDisputeMapper {

    /** 插入 OPEN 工单；自增主键 dispute_id 由驱动回填到 {@code disputeId}。 */
    @Insert("INSERT INTO integration.bureau_disputes "
            + "(customer_ref, bureau, type, due_at, status, trace_id) "
            + "VALUES (#{customerRef}, #{bureau}, #{type}, "
            + "#{dueAt,jdbcType=TIMESTAMP_WITH_TIMEZONE}, 'OPEN', #{traceId,jdbcType=VARCHAR})")
    @Options(useGeneratedKeys = true, keyProperty = "disputeId", keyColumn = "dispute_id")
    int insert(DisputeInsert param);

    /** 查询工单行（行组装在仓储层）。 */
    @Select("SELECT dispute_id, customer_ref, bureau, type, due_at, status "
            + "FROM integration.bureau_disputes WHERE dispute_id = #{disputeId}")
    List<Map<String, Object>> findById(@Param("disputeId") long disputeId);

    /** 推进工单状态（RESOLVED）；返回受影响行数（&gt;0 表示命中）。 */
    @Update("UPDATE integration.bureau_disputes SET status = 'RESOLVED', "
            + "resolution_ref = #{resolutionRef,jdbcType=VARCHAR}, "
            + "trace_id = #{traceId,jdbcType=VARCHAR}, version = version + 1, updated_at = now() "
            + "WHERE dispute_id = #{disputeId}")
    int markResolved(@Param("disputeId") long disputeId,
                     @Param("resolutionRef") String resolutionRef,
                     @Param("traceId") String traceId);

    /**
     * 插入参数对象（可变参数对象；自增主键由驱动回填，未回填时保持 null）。
     */
    class DisputeInsert {

        private String customerRef;
        private String bureau;
        private String type;
        private OffsetDateTime dueAt;
        private String traceId;
        private Long disputeId;

        public DisputeInsert() {
        }

        public DisputeInsert(String customerRef, String bureau, String type,
                             OffsetDateTime dueAt, String traceId) {
            this.customerRef = customerRef;
            this.bureau = bureau;
            this.type = type;
            this.dueAt = dueAt;
            this.traceId = traceId;
        }

        public String getCustomerRef() {
            return customerRef;
        }

        public void setCustomerRef(String customerRef) {
            this.customerRef = customerRef;
        }

        public String getBureau() {
            return bureau;
        }

        public void setBureau(String bureau) {
            this.bureau = bureau;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public OffsetDateTime getDueAt() {
            return dueAt;
        }

        public void setDueAt(OffsetDateTime dueAt) {
            this.dueAt = dueAt;
        }

        public String getTraceId() {
            return traceId;
        }

        public void setTraceId(String traceId) {
            this.traceId = traceId;
        }

        public Long getDisputeId() {
            return disputeId;
        }

        public void setDisputeId(Long disputeId) {
            this.disputeId = disputeId;
        }
    }
}
