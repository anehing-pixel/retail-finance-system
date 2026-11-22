package com.company.retail.servicing.application;

import java.util.Map;

/**
 * 审计落表端口（表 audit.audit_events，append-only 仅 INSERT；R00 §5 / B-9）。
 *
 * <p>actor 一律取<b>验签身份</b>（web 层从拦截器放入的请求属性解析），禁止取自请求体或
 * 客户端头（RB-8）。共享 infrastructure 暂无现成审计机制（W4 报告口径），本服务模块内
 * 自带 JDBC 实现；若中台后续提供共享 AuditWriter，替换实现即可，端口不变。</p>
 */
public interface AuditWriter {

    /**
     * 追加一条审计事件（append-only）。
     *
     * @param actor        验签身份（subject）或 SYSTEM
     * @param actorSubject CUSTOMER / STAFF / SYSTEM / PARTNER
     * @param action       动作码（如 COLLECTION_ACTION / REPORT_TRIGGER_REGISTERED / DPD_BATCH_RUN）
     * @param targetType   目标类型（如 COLLECTION_CASE）
     * @param targetId     目标 ID
     * @param afterSnapshot 动作后快照（只含业务字段，禁 PII）
     * @param traceId      traceId 贯穿
     */
    void write(String actor, String actorSubject, String action, String targetType,
               String targetId, Map<String, Object> afterSnapshot, String traceId);
}
