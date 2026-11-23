package com.company.retail.bureau.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 结清同意锚点解析 Mapper（core.loans → core.contracts → core.underwriting_cases）。
 *
 * <p>跨域只读（ADR-RF-009 第一版共库、按 schema 分区）：只 SELECT，不写任何他域表；
 * 解析出的 consentId 仍由 ConsentGateGuard fail-closed 校验（INV-5）。</p>
 */
public interface SettlementConsentAnchorMapper {

    /** 按债权解析决定时同意锚点（uk_uwc_app 唯一 → 至多一行）。 */
    @Select("SELECT uwc.consent_id FROM core.loans l "
            + "JOIN core.contracts c ON c.contract_id = l.contract_id "
            + "JOIN core.underwriting_cases uwc ON uwc.application_id = c.application_id "
            + "WHERE l.loan_id = #{loanId}")
    List<Long> resolveConsentIds(@Param("loanId") long loanId);
}
