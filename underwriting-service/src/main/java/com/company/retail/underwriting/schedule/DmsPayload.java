package com.company.retail.underwriting.schedule;

/**
 * {@code payload_ref} 的 DMS 结构化编码（P0-7）：{@code caseId|applicationId|decision|reason}。
 *
 * <p>只含业务号与枚举，不含 PII；长度由写入侧截断到 128 内。
 * 历史纯 {@code caseId} 行可解析，但缺少 decision 时调度器会标记 DEAD（人工处理）。</p>
 */
public record DmsPayload(long caseId, long applicationId, String decision, String reasonCode) {

    public static DmsPayload parse(String payloadRef) {
        if (payloadRef == null || payloadRef.isBlank()) {
            throw new IllegalArgumentException("payload_ref 为空");
        }
        String[] parts = payloadRef.split("\\|", -1);
        long caseId = parseLong(parts[0]);
        if (parts.length == 1) {
            // 历史格式：只有 caseId，无法安全重发
            if (caseId <= 0) {
                throw new IllegalArgumentException("caseId 非法");
            }
            return new DmsPayload(caseId, 0, "", "");
        }
        if (parts.length < 3) {
            throw new IllegalArgumentException("payload_ref 字段不足");
        }
        long applicationId = parts[1].isBlank() ? 0 : parseLong(parts[1]);
        String decision = parts[2].trim();
        if (caseId <= 0 || decision.isEmpty()) {
            throw new IllegalArgumentException("caseId/decision 缺失");
        }
        String reason = parts.length > 3 ? parts[3].trim() : "";
        return new DmsPayload(caseId, applicationId, decision, reason);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("payload_ref 数字段非法", e);
        }
    }
}
