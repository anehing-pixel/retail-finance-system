package com.company.retail.bureau.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.contract.ConsentGateFacade;
import com.company.retail.domain.exception.RFErrorCode;

import java.util.Map;

/**
 * 同意门禁 fail-closed 消费侧（bureau 版；INV-5 / RB-1 的查询与报送双落点）。
 *
 * <p>实际门禁查询由同包 Facade（{@code com.company.retail.common.contract.ConsentGateFacade}）
 * 完成——冻结契约 package-private 的桥接方案见 underwriting 同名类。</p>
 *
 * <p>语义与 underwriting-service 的 ConsentGateGuard 一致（common 冻结区不可加代码，
 * 各服务自持一份；收编建议见报告）。</p>
 */
public class ConsentGateGuard {

    private final ConsentGateFacade consentGateFacade;

    public ConsentGateGuard(ConsentGateFacade consentGateFacade) {
        this.consentGateFacade = consentGateFacade;
    }

    /**
     * 校验同意有效且未撤回；不通过即抛 {@code RF_UW_003}（RB-1：不可配置旁路）。
     * 同意缺失/撤回 → <b>不得</b>发起征信查询与报送；历史留痕不删除。
     */
    public void requireConsentValid(long consentId) {
        if (!consentGateFacade.isConsentValid(consentId)) {
            throw new BusinessException(RFErrorCode.RF_UW_003, Map.of(
                    "reason", "CONSENT_GATE_NOT_PASSED",
                    "consentId", String.valueOf(consentId),
                    "note", "fail-closed：查不到/超时/已撤回一律拒绝（INV-5）"));
        }
    }
}
