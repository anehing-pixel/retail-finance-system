package com.company.retail.servicing.infrastructure;

import com.company.retail.servicing.application.AuditWriter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/**
 * audit.audit_events JDBC 落表（append-only 仅 INSERT；R00 §5 / R08 §7 / B-9）。
 *
 * <p>actor 由 web 层从<b>验签身份</b>（拦截器放入的请求属性）解析后传入，
 * 禁止取自请求体或客户端头（RB-8）。共享 infrastructure 暂无现成审计机制，
 * 本实现随 servicing 模块交付；中台若提供共享 AuditWriter 则替换实现，端口不变。</p>
 */
public class JdbcAuditWriter implements AuditWriter {

    private final JdbcTemplate jdbc;

    public JdbcAuditWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void write(String actor, String actorSubject, String action, String targetType,
                      String targetId, Map<String, Object> afterSnapshot, String traceId) {
        jdbc.update("INSERT INTO audit.audit_events "
                        + "(actor, actor_subject, actor_role, action, target_type, target_id, "
                        + " after_snapshot, trace_id) VALUES (?, ?, NULL, ?, ?, ?, ?::jsonb, ?)",
                actor == null || actor.isBlank() ? "SYSTEM" : actor,
                actorSubject == null || actorSubject.isBlank() ? "SYSTEM" : actorSubject,
                action, targetType, targetId,
                toJson(afterSnapshot), traceId);
    }

    private static String toJson(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return "{}";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(snapshot);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("审计快照序列化失败", e);
        }
    }
}
