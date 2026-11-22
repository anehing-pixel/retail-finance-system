package com.company.retail.common.contract;

import com.company.platform.event.JsonPayloadReader;

import java.util.Set;

/**
 * 事件 payload 白名单守卫（INV-4 的发布端执行点，R09 §5.2 §5.3）。
 *
 * <p><b>默认拒绝</b>：payload 键必须全部落在允许键白名单内；命中禁止键或白名单外键即拒绝发布。
 * outbox 表 {@code SENT} 行永久保留——一旦个人信息写入无法撤回，预防是唯一手段。</p>
 *
 * <p>执行顺序：先白名单（结构），再 {@code requireBizKeyAndTraceId}（必填键），最后才允许落库。</p>
 */
public final class EventPayloadGuard {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private EventPayloadGuard() {
    }

    /** 允许键白名单（R09 §5.2）。除此以外的键一律拒绝。 */
    public static final Set<String> ALLOWED_KEYS = Set.of(
            // 元数据
            "eventId", "eventType", "occurredAt", "traceId", "schemaVersion",
            // 业务主键（内部 ID，非个人信息）
            "applicationId", "contractId", "loanId", "caseId", "consentId", "inquiryId",
            "verificationId", "disbursementId", "titleId", "releaseOrderId", "paymentRef",
            "reportId", "dealerPartnerId",
            // 状态/结果
            "status", "outcomeCode", "reasonCode", "scheme", "ruleVersion", "stage",
            // 金额/日期
            "amount", "currency", "bizDate", "dueDate",
            // 技术字段
            "idempotencyKey", "source", "attempt");

    /**
     * 校验 payload：白名单外的键（含禁止键）一律抛 {@link IllegalArgumentException}，事件不落库。
     *
     * <p>只检查<b>顶层键</b>：嵌套对象的键无法逐一登记，白名单语义就是「顶层键全部显式申报」，
     * 需要嵌套结构的字段（如 input_snapshot）不允许进事件载荷。</p>
     *
     * @param eventType 事件名（仅用于错误消息定位生产方）
     * @param payload   待发布的 JSON 文本
     * @throws IllegalArgumentException payload 非法 JSON、含白名单外键、或缺少该事件的业务主键 / traceId
     */
    public static void validate(String eventType, String payload) {
        com.fasterxml.jackson.databind.JsonNode node;
        try {
            node = MAPPER.readTree(payload == null ? "" : payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("事件 payload 不是合法 JSON：" + eventType);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("事件 payload 不是 JSON 对象：" + eventType);
        }
        java.util.List<String> illegal = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(key -> {
            if (!ALLOWED_KEYS.contains(key)) {
                illegal.add(key);
            }
        });
        if (!illegal.isEmpty()) {
            // INV-4：拒绝发布并让错误回到生产方栈上——写进 SENT 表的 PII 永远撤不回
            throw new IllegalArgumentException(
                    "事件 payload 含白名单外的键（R09 §5.3 默认拒绝），拒绝发布 eventType=" + eventType
                            + " illegalKeys=" + illegal);
        }
        JsonPayloadReader.requireBizKeyAndTraceId(payload, OutboxPublisher.bizKeyOf(eventType));
    }
}
