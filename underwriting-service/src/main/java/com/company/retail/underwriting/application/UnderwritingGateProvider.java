package com.company.retail.underwriting.application;

import java.util.Optional;

/**
 * 代付前审查门禁 provider 侧（R09 §7.1：retail-core 代付前第三问）。
 *
 * <p>语义：{@code allowed=true} 当且仅当审查案件处于 {@code APPROVED} 且未过期；
 * 其余（含案件不存在）一律 {@code allowed=false}——门禁判定 fail-closed，
 * 调用方只认 allowed==true。</p>
 */
public class UnderwritingGateProvider {

    private final UnderwritingCaseRepository caseRepository;

    public UnderwritingGateProvider(UnderwritingCaseRepository caseRepository) {
        this.caseRepository = caseRepository;
    }

    public Optional<GateView> check(long caseId) {
        return caseRepository.findById(caseId)
                .map(testCase -> new GateView(
                        testCase.getStatus() == com.company.retail.underwriting.domain.UnderwritingStatus.APPROVED,
                        testCase.getStatus().name()));
    }

    /** 门禁结果视图（JSON 形状与冻结契约 {@code GateResult} 一致：{allowed, reasonCode}）。 */
    public record GateView(boolean allowed, String reasonCode) {
    }
}
