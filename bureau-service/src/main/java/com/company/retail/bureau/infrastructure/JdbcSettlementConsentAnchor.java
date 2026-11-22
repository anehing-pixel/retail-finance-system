package com.company.retail.bureau.infrastructure;

import com.company.retail.bureau.application.SettlementConsentAnchorPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/**
 * 结清同意锚点 JDBC 解析（core.loans → core.contracts → core.underwriting_cases）。
 *
 * <p>跨域只读（ADR-RF-009 第一版共库、按 schema 分区）：不写任何他域表，
 * 解析出的 consentId 仍由 ConsentGateGuard fail-closed 校验（INV-5）。</p>
 */
public class JdbcSettlementConsentAnchor implements SettlementConsentAnchorPort {

    private final JdbcTemplate jdbc;

    public JdbcSettlementConsentAnchor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> resolveConsentId(long loanId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT uwc.consent_id FROM core.loans l "
                        + "JOIN core.contracts c ON c.contract_id = l.contract_id "
                        + "JOIN core.underwriting_cases uwc ON uwc.application_id = c.application_id "
                        + "WHERE l.loan_id = ?",
                Long.class, loanId);
        return ids.isEmpty() ? Optional.empty() : Optional.ofNullable(ids.get(0));
    }
}
