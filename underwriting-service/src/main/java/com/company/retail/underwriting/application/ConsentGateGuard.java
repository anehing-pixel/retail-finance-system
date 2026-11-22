package com.company.retail.underwriting.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.contract.ConsentGateFacade;
import com.company.retail.domain.exception.RFErrorCode;

import java.util.Map;

/**
 * 同意门禁 fail-closed 消费侧（G3 / INV-5 / RB-1）。
 *
 * <p>实际门禁查询由同包 Facade（{@code com.company.retail.common.contract.ConsentGateFacade}）
 * 完成——冻结契约 {@code FeignContracts.ConsentGateClient} 是 package-private，跨包不可命名，
 * Facade 在同包桥接（只消费冻结契约，不新增契约；下沉 common 的建议见报告）。</p>
 *
 * <p><b>fail-closed 语义</b>：只有 {@code 2xx && code==OK && allowed==true} 才推进；
 * 查不到 / 超时 / UNKNOWN / 非 2xx → {@code RF_UW_003} 拒绝，宁可误拒不可放过。
 * 禁止缓存门禁结果（同意可秒级撤回，R09 §7.3）。</p>
 */
public class ConsentGateGuard {

    private final ConsentGateFacade consentGateFacade;

    public ConsentGateGuard(ConsentGateFacade consentGateFacade) {
        this.consentGateFacade = consentGateFacade;
    }

    /** 校验同意有效且未撤回；不通过即抛 {@code RF_UW_003}（硬阻断，不可配置旁路）。 */
    public void requireConsentValid(long consentId) {
        if (!consentGateFacade.isConsentValid(consentId)) {
            throw new BusinessException(RFErrorCode.RF_UW_003, Map.of(
                    "reason", "CONSENT_GATE_NOT_PASSED",
                    "consentId", String.valueOf(consentId),
                    "note", "fail-closed：查不到/超时/已撤回一律拒绝"));
        }
    }
}
