package com.company.retail.bureau.acl;

import com.company.retail.bureau.domain.CreditBureau;

import java.util.UUID;

/**
 * JICC 挡板适配器（R11 W4：不接真实专线，报文为合成留痕）。行为口径与 CIC 挡板一致。
 */
public class JiccStubAdapter implements BureauAdapter {

    @Override
    public CreditBureau bureau() {
        return CreditBureau.JICC;
    }

    @Override
    public InquiryOutcome inquire(BureauInquiryCommand command) {
        if (command == null || command.consentId() <= 0) {
            return InquiryOutcome.failed("{\"stub\":\"JICC\",\"error\":\"CONSENT_ID_REQUIRED\"}");
        }
        String inquiryRef = "JICC-STUB-" + UUID.randomUUID();
        String message = "{\"stub\":\"JICC\",\"type\":\"INQUIRY\",\"ref\":\"" + inquiryRef
                + "\",\"caseId\":" + command.caseId()
                + ",\"purpose\":\"" + command.purpose() + "\""
                + ",\"scoreBand\":\"B\"}";
        return InquiryOutcome.completed(inquiryRef, message, 400);
    }

    @Override
    public ReportOutcome submitReport(BureauReportCommand command) {
        String reportRef = "JICC-STUB-RPT-" + UUID.randomUUID();
        String message = "{\"stub\":\"JICC\",\"type\":\"REPORT\",\"ref\":\"" + reportRef
                + "\",\"loanId\":" + command.loanId()
                + ",\"trigger\":\"" + command.trigger() + "\""
                + ",\"bizDate\":\"" + command.bizDate() + "\""
                + ",\"accepted\":true}";
        return ReportOutcome.sent(reportRef, message);
    }

    @Override
    public DisputeOutcome fileDispute(BureauDisputeCommand command) {
        String disputeRef = "JICC-STUB-DSP-" + UUID.randomUUID();
        String message = "{\"stub\":\"JICC\",\"type\":\"DISPUTE\",\"ref\":\"" + disputeRef
                + "\",\"disputeId\":" + command.disputeId()
                + ",\"accepted\":true}";
        return new DisputeOutcome(true, disputeRef, message);
    }
}
