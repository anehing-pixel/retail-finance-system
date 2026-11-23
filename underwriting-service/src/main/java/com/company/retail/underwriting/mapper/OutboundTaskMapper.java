package com.company.retail.underwriting.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 出站任务 Mapper（MyBatis 注解 SQL；表 {@code integration.outbound_tasks}）。
 *
 * <p>仅用于 DMS 结果通知推送失败后的人工补发留痕（PENDING）；权威状态在案件表，
 * 故不因 DMS 不可达阻断事件消费（R09 §5.4 例外声明）。</p>
 */
public interface OutboundTaskMapper {

    /** 落一条 PENDING 人工补发任务（partner_id 固定 0 = DMS 结果通知）。 */
    @Insert("INSERT INTO integration.outbound_tasks "
            + "(partner_id, task_type, payload_ref, status, retry_count, trace_id) "
            + "VALUES (0, 'DMS_RESULT_NOTIFY', #{payloadRef}, 'PENDING', 0, #{traceId,jdbcType=VARCHAR})")
    int insertPending(@Param("payloadRef") String payloadRef, @Param("traceId") String traceId);
}
