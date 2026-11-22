package com.company.retail.underwriting.domain;

/**
 * 审查决定结果（R00 §3.4 / R04 §3.2）。
 */
public enum DecisionOutcome {

    /** 批准（发布 UNDERWRITING_APPROVED）。 */
    APPROVED,
    /** 拒绝（发布 UNDERWRITING_DECLINED）。 */
    DECLINED,
    /** 转人工复核（复核人 ≠ 决定人，SOD 硬约束）。 */
    REFERRED
}
