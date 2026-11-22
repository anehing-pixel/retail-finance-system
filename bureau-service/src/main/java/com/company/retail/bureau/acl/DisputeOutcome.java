package com.company.retail.bureau.acl;

/**
 * 异议处理结果（ACL 译出后形状；订正报送留痕进报文留痕表）。
 *
 * @param accepted     机构是否受理
 * @param registryRef  机构受理/订正引用码
 * @param rawMessage   机构应答报文原文（仅进报文留痕表）
 */
public record DisputeOutcome(boolean accepted, String registryRef, String rawMessage) {
}
