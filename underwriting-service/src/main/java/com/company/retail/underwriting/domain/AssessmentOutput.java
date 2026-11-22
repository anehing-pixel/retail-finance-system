package com.company.retail.underwriting.domain;

import java.util.Map;

/**
 * 支付能力评估结果值（A-6 的结果快照，只存输入与结果，不重算历史）。
 *
 * @param result        PASS / FAIL / REFER（REFER = 人工复核通道）
 * @param reasonCode    判定理由码（白名单内，禁止 PII）
 * @param policyVersion 策略版本（进入 core.affordability_assessments.policy_version）
 */
public record AssessmentOutput(AssessmentResult result, String reasonCode, String policyVersion) {

    public AssessmentOutput {
        if (reasonCode == null || reasonCode.isBlank()) {
            reasonCode = "NONE";
        }
    }

    /** 结果快照（input_snapshot JSONB 用，禁 PII——只有结果码与策略版本）。 */
    public Map<String, Object> snapshot() {
        return Map.of("result", result.name(), "reasonCode", reasonCode, "policyVersion", policyVersion);
    }
}
