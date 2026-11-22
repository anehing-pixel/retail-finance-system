package com.company.retail.bureau.acl;

import com.company.retail.bureau.domain.CreditBureau;

import java.util.UUID;

/**
 * CIC 挡板适配器（R11 W4：不接真实专线，报文为合成留痕）。
 *
 * <p>挡板行为：查询/报送/异议全部受理成功，返回确定性合成报文（含业务引用，
 * 不含任何真实个人信息）。真实接入审查通过后替换本类实现，接口与调用方不变。</p>
 */
public class CicStubAdapter implements BureauAdapter {

    public static final String STUB_MARKER = "STUB";

    @Override
    public CreditBureau bureau() {
        return CreditBureau.CIC;
    }

    @Override
    public InquiryOutcome inquire(BureauInquiryCommand command) {
        if (command == null || command.consentId() <= 0) {
            // INV-5 双兜底：适配器层面再挡一次无同意查询
            return InquiryOutcome.failed("{\"stub\":\"CIC\",\"error\":\"CONSENT_ID_REQUIRED\"}");
        }
        String inquiryRef = "CIC-STUB-" + UUID.randomUUID();
        String message = "{\"stub\":\"CIC\",\"type\":\"INQUIRY\",\"ref\":\"" + inquiryRef
                + "\",\"caseId\":" + command.caseId()
                + ",\"purpose\":\"" + command.purpose() + "\""
                + ",\"scoreBand\":\"B\"}";
        return InquiryOutcome.completed(inquiryRef, message, 500);
    }

    @Override
    public ReportOutcome submitReport(BureauReportCommand command) {
        String reportRef = "CIC-STUB-RPT-" + UUID.randomUUID();
        String message = "{\"stub\":\"CIC\",\"type\":\"REPORT\",\"ref\":\"" + reportRef
                + "\",\"loanId\":" + command.loanId()
                + ",\"trigger\":\"" + command.trigger() + "\""
                + ",\"bizDate\":\"" + command.bizDate() + "\""
                + ",\"accepted\":true}";
        return ReportOutcome.sent(reportRef, message);
    }

    @Override
    public DisputeOutcome fileDispute(BureauDisputeCommand command) {
        String disputeRef = "CIC-STUB-DSP-" + UUID.randomUUID();
        String message = "{\"stub\":\"CIC\",\"type\":\"DISPUTE\",\"ref\":\"" + disputeRef
                + "\",\"disputeId\":" + command.disputeId()
                + ",\"accepted\":true}";
        return new DisputeOutcome(true, disputeRef, message);
    }
}
