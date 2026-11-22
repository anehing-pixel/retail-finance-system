package com.company.retail.underwriting.domain;

/**
 * 支付能力评估结果（挡板口径，非 C-03 公式）。
 *
 * <ul>
 *   <li>{@code PASS}：挡板判定通过，可进入决定；</li>
 *   <li>{@code FAIL}：超支付能力预估额 / 总量规制上限 → {@code RF_UW_002}（R05 G5）；</li>
 *   <li>{@code REFER}：输入不全或策略点关闭 → 转人工复核通道（R05：人工路径一等公民）。</li>
 * </ul>
 */
public enum AssessmentResult {
    PASS,
    FAIL,
    REFER
}
