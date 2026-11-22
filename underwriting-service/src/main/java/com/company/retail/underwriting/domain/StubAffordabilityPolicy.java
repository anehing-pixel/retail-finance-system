package com.company.retail.underwriting.domain;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;

import java.util.Map;

/**
 * {@link AffordabilityPolicy} 的<b>挡板实现</b>（R11 W4：不实现 C-03 公式，只留策略点挂钩）。
 *
 * <p>挡板判定口径（刻意简单、可预测，供联调与测试；<b>不是</b>个别支付能力预估额公式）：</p>
 * <ul>
 *   <li>策略点开关关闭（{@code enabled=false}）→ {@code REFER}（人工复核通道）；</li>
 *   <li>收入证明引用码缺失 → {@code REFER}（人工复核通道，G5）；</li>
 *   <li>申请金额 &gt; 预估额上限 → {@code FAIL}（应用层转 {@code RF_UW_002}）；</li>
 *   <li>其余 → {@code PASS}。</li>
 * </ul>
 *
 * <p>金额一律整数日元（long），禁止浮点参与金额（R00 §5）。</p>
 */
public class StubAffordabilityPolicy implements AffordabilityPolicy {

    public static final String VERSION = "STUB-1.0";

    private final boolean enabled;

    public StubAffordabilityPolicy(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String policyVersion() {
        return VERSION;
    }

    @Override
    public AssessmentOutput assess(AffordabilityInput input) {
        if (input == null) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", "input"));
        }
        // 策略点可配置开关：关闭即全部转人工（不静默放行——宁可误拒不可放过）
        if (!enabled) {
            return new AssessmentOutput(AssessmentResult.REFER, "POLICY_DISABLED", VERSION);
        }
        if (input.scheme() == null || input.scheme().isBlank()) {
            // C-01：每笔必填形态字段，不设兜底默认
            throw new BusinessException(RFErrorCode.RF_DMS_003);
        }
        if (input.annualIncomeEncRef() == null || input.annualIncomeEncRef().isBlank()) {
            return new AssessmentOutput(AssessmentResult.REFER, "INCOME_DOCUMENT_MISSING", VERSION);
        }
        if (input.thresholdAmount() <= 0) {
            return new AssessmentOutput(AssessmentResult.REFER, "THRESHOLD_NOT_CONFIGURED", VERSION);
        }
        if (input.requestedAmount() > input.thresholdAmount()) {
            return new AssessmentOutput(AssessmentResult.FAIL, "OVER_AFFORDABILITY_THRESHOLD", VERSION);
        }
        return new AssessmentOutput(AssessmentResult.PASS, "WITHIN_THRESHOLD", VERSION);
    }
}
