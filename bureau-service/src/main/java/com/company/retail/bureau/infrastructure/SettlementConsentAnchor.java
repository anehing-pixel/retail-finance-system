package com.company.retail.bureau.infrastructure;

import com.company.retail.bureau.application.SettlementConsentAnchorPort;
import com.company.retail.bureau.mapper.SettlementConsentAnchorMapper;

import java.util.List;
import java.util.Optional;

/**
 * 结清同意锚点 MyBatis 解析（core.loans → core.contracts → core.underwriting_cases）。
 *
 * <p>跨域只读（ADR-RF-009 第一版共库、按 schema 分区）：不写任何他域表，
 * 解析出的 consentId 仍由 ConsentGateGuard fail-closed 校验（INV-5）。</p>
 */
public class SettlementConsentAnchor implements SettlementConsentAnchorPort {

    private final SettlementConsentAnchorMapper mapper;

    public SettlementConsentAnchor(SettlementConsentAnchorMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Long> resolveConsentId(long loanId) {
        List<Long> ids = mapper.resolveConsentIds(loanId);
        return ids.isEmpty() ? Optional.empty() : Optional.ofNullable(ids.get(0));
    }
}
