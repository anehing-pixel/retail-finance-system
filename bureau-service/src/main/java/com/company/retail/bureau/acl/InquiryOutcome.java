package com.company.retail.bureau.acl;

/**
 * 查询结果（ACL 译出后的内部形状；机构报文原文走报文留痕，不进领域模型）。
 *
 * <p>硬规则（ADR-RF-005）：不写空结果、不留假 COMPLETED；失败也必须留痕。</p>
 *
 * @param status        COMPLETED / FAILED
 * @param resultSummary 结果摘要引用码（机构评分/区间摘要，非明文 PII）
 * @param rawMessage    机构报文原文（挡板为合成报文；真实接入为专线报文）——仅进报文留痕表
 * @param costAmount    查询费用（整数日元，按次计费留痕）
 */
public record InquiryOutcome(String status, String resultSummary, String rawMessage, long costAmount) {

    public static InquiryOutcome completed(String resultSummary, String rawMessage, long costAmount) {
        return new InquiryOutcome("COMPLETED", resultSummary, rawMessage, costAmount);
    }

    public static InquiryOutcome failed(String rawMessage) {
        return new InquiryOutcome("FAILED", null, rawMessage, 0);
    }
}
