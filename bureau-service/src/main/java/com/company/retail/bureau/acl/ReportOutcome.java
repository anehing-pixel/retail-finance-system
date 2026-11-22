package com.company.retail.bureau.acl;

/**
 * 报送结果（ACL 译出后形状）。失败保留失败报文 + 差异工单，不静默丢弃（X-11）。
 *
 * @param status      SENT / REJECTED
 * @param responseRef 机构受理引用码
 * @param rawMessage  机构应答报文原文（仅进报文留痕表）
 */
public record ReportOutcome(String status, String responseRef, String rawMessage) {

    public static ReportOutcome sent(String responseRef, String rawMessage) {
        return new ReportOutcome("SENT", responseRef, rawMessage);
    }

    public static ReportOutcome rejected(String rawMessage) {
        return new ReportOutcome("REJECTED", null, rawMessage);
    }
}
