package com.company.retail.customer.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * PII 访问日志 Mapper（audit.pii_access_logs，R06 §4.8）。
 *
 * <p>append-only：只记谁/何时/哪个客户/哪个字段/用途，<b>不记明文值</b>（R08 §5 PII-5 / R09 §5）。</p>
 */
public interface PiiAccessLogMapper {

    /** 追加一条 PII 访问留痕。 */
    @Insert("INSERT INTO audit.pii_access_logs (actor, actor_subject, customer_id, fields, purpose, trace_id) "
            + "VALUES (#{actor}, #{actorSubject}, #{customerId}, #{fields}, #{purpose}, #{traceId})")
    int record(@Param("actor") String actor,
               @Param("actorSubject") String actorSubject,
               @Param("customerId") long customerId,
               @Param("fields") String fields,
               @Param("purpose") String purpose,
               @Param("traceId") String traceId);
}
