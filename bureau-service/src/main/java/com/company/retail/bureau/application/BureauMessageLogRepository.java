package com.company.retail.bureau.application;

/**
 * 机构报文留痕仓储端口（integration.bureau_message_logs，V11 增量表）。
 *
 * <p><b>报文留痕</b>（ADR-RF-005 §2 + R05 X-11）：机构报文原文（挡板为合成报文、
 * 真实接入为专线报文）逐条 append-only 留痕；查询失败/报送被拒的报文同样保留，
 * 不静默丢弃。报文内容<b>不进事件 payload、不进应用日志</b>（PII-4）。</p>
 */
public interface BureauMessageLogRepository {

    /** 追加一条报文留痕（direction: OUTBOUND 查询/报送请求，INBOUND 机构应答）。 */
    void append(String direction, String bureau, String bizRef, String messageText, String traceId);

    /** 按业务引用统计留痕条数（留痕存在性断言用）。 */
    long countByBizRef(String bizRef);
}
