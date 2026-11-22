package com.company.retail.bureau.domain;

/**
 * 征信查询状态（R06 core.credit_inquiries.status，挡板口径）。
 *
 * <p>硬规则（ADR-RF-005 §2）：不写空结果、不留假 COMPLETED；
 * 失败保留留痕（attempt 行），3 次重试后转人工（R05 G4 / X-2）。</p>
 */
public enum InquiryStatus {
    /** 已受理（发起留痕，专线等待中）。 */
    REQUESTED,
    /** 查询完成（结果落快照引用，append-only）。 */
    COMPLETED,
    /** 查询失败（保留失败报文留痕，可重试）。 */
    FAILED
}
