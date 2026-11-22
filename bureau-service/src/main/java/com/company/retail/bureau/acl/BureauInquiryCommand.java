package com.company.retail.bureau.acl;

import com.company.retail.bureau.domain.CreditBureau;

/**
 * 查询指令（内部 Command，P-8：外部 DTO 必经 ACL，机构报文不直接落领域对象）。
 *
 * @param caseId     审查案件 ID（core.credit_inquiries.case_id）
 * @param consentId  同意 ID（INV-5：必填，DB NOT NULL + 应用层双兜底）
 * @param bureau     目标机构
 * @param purpose    查询用途（InquiryPurpose，留痕）
 */
public record BureauInquiryCommand(long caseId, long consentId, CreditBureau bureau, String purpose) {
}
