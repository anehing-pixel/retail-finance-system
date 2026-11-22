package com.company.retail.bureau.acl;

import com.company.retail.bureau.domain.CreditBureau;

/**
 * 异议申诉指令（内部 Command；查阅/更正请求对应链路，C-05/C-06）。
 *
 * @param disputeId   工单 ID
 * @param bureau      目标机构
 * @param contentRef  异议内容引用码（不落 PII 明文）
 */
public record BureauDisputeCommand(long disputeId, CreditBureau bureau, String contentRef) {
}
