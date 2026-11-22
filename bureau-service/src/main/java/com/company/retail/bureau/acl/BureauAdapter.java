package com.company.retail.bureau.acl;

import com.company.retail.bureau.domain.CreditBureau;

/**
 * 防腐层端口：机构适配器（ADR-RF-005 §3）。
 *
 * <p><b>唯一对接征信机构的出口/入口</b>：机构编码、报文格式、故障语义全部被
 * 适配器翻译成内部 Command/Outcome 形状，不进入领域模型（P-8 外部必经 ACL）。
 * 机构组合与报送口径由策略点（CreditBureauReportingPolicy）承载，与本端口分工。</p>
 *
 * <p>当前全部为<b>挡板实现</b>（R11 W4：不接真实专线）；真实接入时按机构各替换一个适配器，
 * 人工通道（source=MANUAL）与自动通道汇入同一用例服务（ADR-RF-005 §5）。</p>
 */
public interface BureauAdapter {

    /** 本适配器服务的机构。 */
    CreditBureau bureau();

    InquiryOutcome inquire(BureauInquiryCommand command);

    ReportOutcome submitReport(BureauReportCommand command);

    DisputeOutcome fileDispute(BureauDisputeCommand command);
}
