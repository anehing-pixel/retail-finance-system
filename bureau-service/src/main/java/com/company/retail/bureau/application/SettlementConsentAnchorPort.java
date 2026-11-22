package com.company.retail.bureau.application;

import java.util.Optional;

/**
 * 结清报送的同意锚点解析端口（LOAN_SETTLED payload 只有 loanId/contractId 等业务主键，
 * INV-5 要求 integration.credit_bureau_reports.consent_id NOT NULL）。
 *
 * <p><b>锚点链</b>：core.loans.contract_id → core.contracts.application_id →
 * core.underwriting_cases.consent_id（V11「决定时同意锚点」，uk_uwc_app 唯一）。
 * 跨域只读；解析后仍须经 ConsentGateGuard fail-closed 校验（BureauReportAppService 内）。</p>
 *
 * <p>已登记最终报告共享区建议：LOAN_SETTLED payload 增加 consentId（白名单已有该键），
 * 届时本端口可退化为直读 payload。</p>
 */
public interface SettlementConsentAnchorPort {

    /** 按债权解析决定时同意锚点；链路缺失（无合同/无审查案件）返回 empty。 */
    Optional<Long> resolveConsentId(long loanId);
}
