package com.company.retail.underwriting.domain;

/**
 * 支付能力评估输入（A-6 AffordabilityAssessment 的评估入参快照字段）。
 *
 * <p>PII 纪律：年收入只带<b>加密引用码</b>（{@code annualIncomeEncRef}），不传明文金额、
 * 不传姓名等个人标识（R08 PII-3/PII-4）。</p>
 *
 * @param scheme            交易形态（C-01 双形态，每笔必填，无兜底默认）
 * @param annualIncomeEncRef 收入证明加密引用码（可空：缺失走人工复核通道）
 * @param existingDebtTotal  他社借入余额合计（整数日元）
 * @param thresholdAmount    支付能力预估额 / 总量规制上限（整数日元，来自规则配置）
 * @param requestedAmount    本笔申请金额（整数日元）
 */
public record AffordabilityInput(String scheme,
                                 String annualIncomeEncRef,
                                 long existingDebtTotal,
                                 long thresholdAmount,
                                 long requestedAmount) {
}
