package com.company.retail.underwriting.domain;

/**
 * 审查状态机（唯一来源 R04 §3.2，状态值 UPPER_SNAKE）。
 *
 * <pre>
 * PENDING → INQUIRY_IN_PROGRESS → INQUIRY_DONE → ASSESSING → (APPROVED / DECLINED / REFERRED) → EXPIRED(终态)
 *                                  INQUIRY_IN_PROGRESS → INQUIRY_FAILED（可重试，3 次后转人工）
 *                                  INQUIRY_FAILED → INQUIRY_IN_PROGRESS（重试入口）
 *                                  REFERRED → APPROVED / DECLINED（人工复核，复核人 ≠ 决定人）
 * </pre>
 *
 * <p>硬规则（R00 §5）：状态变更只能走聚合方法（{@code UnderwritingCase}），禁止直接 UPDATE status；
 * 终态不可再迁移。</p>
 */
public enum UnderwritingStatus {

    /** 待启动：案件建立，尚未发起征信查询。 */
    PENDING,
    /** 征信查询进行中。 */
    INQUIRY_IN_PROGRESS,
    /** 征信查询完成（未完成不得决定，R04 U-3）。 */
    INQUIRY_DONE,
    /** 评估中（支付能力评估 + 规则判定）。 */
    ASSESSING,
    /** 审查批准（可转 EXPIRED：承認有効期过）。 */
    APPROVED,
    /** 审查拒绝（终态）。 */
    DECLINED,
    /** 转人工复核（复核人 ≠ 决定人，SOD）。 */
    REFERRED,
    /** 承認有効期已过（终态）。 */
    EXPIRED,
    /** 征信查询失败（可重试；3 次后转人工，R05 G4/X-2）。 */
    INQUIRY_FAILED;

    /** 终态集合显式列举（R04 I-7）：不可再迁移。 */
    public boolean isTerminal() {
        return this == DECLINED || this == EXPIRED;
    }

    /** 状态机穷举：只允许本表列出的迁移，其余一律 false（调用方抛 RF_VALID_002）。 */
    public boolean canTransitionTo(UnderwritingStatus target) {
        return switch (this) {
            case PENDING -> target == INQUIRY_IN_PROGRESS;
            case INQUIRY_IN_PROGRESS -> target == INQUIRY_DONE || target == INQUIRY_FAILED;
            case INQUIRY_FAILED -> target == INQUIRY_IN_PROGRESS; // 重试入口
            case INQUIRY_DONE -> target == ASSESSING;             // 不得跳过 INQUIRY_DONE
            case ASSESSING -> target == APPROVED || target == DECLINED || target == REFERRED;
            case REFERRED -> target == APPROVED || target == DECLINED; // 人工复核
            case APPROVED -> target == EXPIRED;                   // 承認有効期
            case DECLINED, EXPIRED -> false;                      // 终态
        };
    }
}
