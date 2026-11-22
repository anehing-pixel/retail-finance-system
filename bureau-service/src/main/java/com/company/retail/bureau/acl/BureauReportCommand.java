package com.company.retail.bureau.acl;

import com.company.retail.bureau.domain.CreditBureau;

/**
 * 报送指令（内部 Command；合同内容/还款状况 T+1 报送，C-05）。
 *
 * @param loanId    债权 ID（integration.credit_bureau_reports.loan_id）
 * @param consentId 同意 ID（INV-5 必填）
 * @param bureau    报送目标机构（C-05：主 CIC）
 * @param trigger   报送触发（ReportTrigger：SETTLED / MONTHLY / MANUAL 等）
 * @param bizDate   报送业务日期（T+1 口径由策略点给出）
 */
public record BureauReportCommand(long loanId, long consentId, CreditBureau bureau,
                                  String trigger, String bizDate) {
}
