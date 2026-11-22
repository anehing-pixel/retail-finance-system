package com.company.retail.contract.domain;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 合同域策略（C-08 裁决值实现，参数可配置覆盖，R04 §5.2 {@code StatutoryDocumentPolicy}）。
 *
 * <p><b>法务效力判断不实现</b>：模板是否合规、冷静期例外是否适用，都不在本类职责内——
 * 本类只产出「必须交付哪些模板」与「是否进入冷静期占位」两个可配置口径。</p>
 */
public class ContractPolicy {

    private final Set<String> requiredDocTypes;
    private final boolean coolingOffEnabled;
    private final int coolingOffDays;

    /**
     * @param requiredDocTypes   签订前必须交付留痕的书面文件类型清单（模板可配）
     * @param coolingOffEnabled  是否进入冷静期占位（C-08：配置化，不写死）
     * @param coolingOffDays     冷静期天数占位（C-08 裁决：默认 8，核实不符改配置）
     */
    public ContractPolicy(Set<String> requiredDocTypes, boolean coolingOffEnabled, int coolingOffDays) {
        if (requiredDocTypes == null || requiredDocTypes.isEmpty()) {
            throw new IllegalArgumentException("签订前必须声明至少一种法定书面文件类型（RF_CTR_001 的判定基准）");
        }
        if (coolingOffDays <= 0) {
            throw new IllegalArgumentException("冷静期天数占位必须为正整数（C-08 默认 8 日）");
        }
        this.requiredDocTypes = Set.copyOf(requiredDocTypes);
        this.coolingOffEnabled = coolingOffEnabled;
        this.coolingOffDays = coolingOffDays;
    }

    /** 该交易形态必须交付的书面文件类型（模板可配）。 */
    public Set<String> requiredDocTypes(TransactionScheme scheme) {
        return requiredDocTypes;
    }

    /** 是否进入冷静期占位（C-08：个品分期下的解约权；配置开关 + 双形态下仅个品分期适用）。 */
    public boolean coolingOffApplicable(TransactionScheme scheme) {
        return coolingOffEnabled && scheme == TransactionScheme.INSTALLMENT_CREDIT;
    }

    /** 冷静期天数占位（C-08 默认 8）。 */
    public int coolingOffDays() {
        return coolingOffDays;
    }

    /**
     * G7 守卫（RF_CTR_001）：法定书面文件未交付不得签订。
     * 只判定「必须交付的类型已有交付留痕行」，不判断文件内容效力（C-08 分界）。
     */
    public void assertAllRequiredDelivered(TransactionScheme scheme, List<StatutoryDocument> documents) {
        Set<String> delivered = documents == null ? Set.of() : documents.stream()
                .filter(StatutoryDocument::isDelivered)
                .map(StatutoryDocument::docType)
                .collect(Collectors.toSet());
        List<String> missing = requiredDocTypes(scheme).stream()
                .filter(t -> !delivered.contains(t))
                .toList();
        if (!missing.isEmpty()) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_CTR_001,
                    "法定书面文件未交付，不得签订（G7）",
                    java.util.Map.of("missingDocTypes", String.join(",", missing)));
        }
    }
}
