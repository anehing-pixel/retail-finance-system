package com.company.retail.underwriting.domain;

/**
 * 策略点：支付能力预估额（ADR-RF-002 四策略点之一，上下文 underwriting，C-03）。
 *
 * <p><b>R01 §6 裁决口径</b>：A 形态按个别支付能力预估额框架实现（系数可配、拒绝码枚举预留）；
 * B 形态按自动车担保除外贷付建模（不计总量规制）；默认公式 + 配置覆盖。
 * <b>本轮 S2 明确不实现 C-03 官方公式</b>——只留策略点挂钩（接口 + 挡板实现），
 * 公式落地时替换本接口实现即可，不动链路其他代码。</p>
 *
 * <p>实现要求：判定结果只存输入与结果快照（append-only，不重算历史）；法务效力判断不做
 * （R01 §8 分界）。</p>
 */
public interface AffordabilityPolicy {

    /** 策略版本（落 core.affordability_assessments.policy_version）。 */
    String policyVersion();

    /**
     * 支付能力判定（挡板或真公式）。
     *
     * @return PASS / FAIL（超限，触发 RF_UW_002）/ REFER（转人工复核通道）
     */
    AssessmentOutput assess(AffordabilityInput input);
}
