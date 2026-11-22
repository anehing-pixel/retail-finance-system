package com.company.retail.bureau.acl;

import com.company.retail.bureau.domain.CreditBureau;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防腐层挡板适配器单测（ADR-RF-005 §3：机构报文经 ACL 译成内部形状；机构编码不进 domain）。
 */
class StubAdaptersTest {

    private final CicStubAdapter cic = new CicStubAdapter();
    private final JiccStubAdapter jicc = new JiccStubAdapter();

    @Test
    void cicInquiry_returnsCompletedWithMessageTrace() {
        InquiryOutcome out = cic.inquire(new BureauInquiryCommand(1L, 501L, CreditBureau.CIC, "UNDERWRITING"));
        assertEquals("COMPLETED", out.status());
        assertNotNull(out.resultSummary());
        assertTrue(out.rawMessage().contains("\"stub\":\"CIC\""));
        assertTrue(out.costAmount() > 0);
    }

    @Test
    void jiccInquiry_returnsCompletedWithMessageTrace() {
        InquiryOutcome out = jicc.inquire(new BureauInquiryCommand(1L, 501L, CreditBureau.JICC, "UNDERWRITING"));
        assertEquals("COMPLETED", out.status());
        assertTrue(out.rawMessage().contains("\"stub\":\"JICC\""));
    }

    @Test
    void inquiryWithoutConsent_isRejectedByAdapterToo() {
        // INV-5 双兜底：适配器层面再挡一次无同意查询
        InquiryOutcome out = cic.inquire(new BureauInquiryCommand(1L, 0L, CreditBureau.CIC, "UNDERWRITING"));
        assertEquals("FAILED", out.status());
        assertTrue(out.rawMessage().contains("CONSENT_ID_REQUIRED"));
    }

    @Test
    void cicReport_returnsSentWithMessageTrace() {
        ReportOutcome out = cic.submitReport(new BureauReportCommand(10L, 501L, CreditBureau.CIC, "SETTLED", "2026-11-23"));
        assertEquals("SENT", out.status());
        assertNotNull(out.responseRef());
        assertTrue(out.rawMessage().contains("\"type\":\"REPORT\""));
    }

    @Test
    void jiccReport_returnsSent() {
        ReportOutcome out = jicc.submitReport(new BureauReportCommand(10L, 501L, CreditBureau.JICC, "MONTHLY", "2026-11-23"));
        assertEquals("SENT", out.status());
    }

    @Test
    void disputeFiles_acceptedWithRegistryRef() {
        DisputeOutcome out = cic.fileDispute(new BureauDisputeCommand(7L, CreditBureau.CIC, "REF-1"));
        assertTrue(out.accepted());
        assertNotNull(out.registryRef());
        DisputeOutcome outJ = jicc.fileDispute(new BureauDisputeCommand(7L, CreditBureau.JICC, "REF-1"));
        assertTrue(outJ.accepted());
    }

    @Test
    void stubMessages_containNoPersonalData() {
        // 挡板报文为确定性合成内容：不含姓名/电话等任何真实个人信息（挡板也不留 PII 习惯）
        InquiryOutcome out = cic.inquire(new BureauInquiryCommand(1L, 501L, CreditBureau.CIC, "UNDERWRITING"));
        assertFalse(out.rawMessage().toLowerCase().contains("name"));
        assertFalse(out.rawMessage().toLowerCase().contains("phone"));
        assertFalse(out.rawMessage().toLowerCase().contains("address"));
    }
}
