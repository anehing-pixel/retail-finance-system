package com.company.retail.servicing.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 审计落表 MyBatis Mapper（表 audit.audit_events，append-only 仅 INSERT；R00 §5 / R08 §7 / B-9）。
 *
 * <p>actor 由 web 层从<b>验签身份</b>（拦截器放入的请求属性）解析后传入，
 * 禁止取自请求体或客户端头（RB-8）。</p>
 */
public interface AuditMapper {

    @Insert("INSERT INTO audit.audit_events (actor, actor_subject, actor_role, action, target_type, "
            + "target_id, after_snapshot, trace_id) VALUES (#{actor}, #{actorSubject}, NULL, #{action}, "
            + "#{targetType}, #{targetId}, #{afterSnapshot}::jsonb, #{traceId})")
    int insert(@Param("actor") String actor,
               @Param("actorSubject") String actorSubject,
               @Param("action") String action,
               @Param("targetType") String targetType,
               @Param("targetId") String targetId,
               @Param("afterSnapshot") String afterSnapshot,
               @Param("traceId") String traceId);
}
