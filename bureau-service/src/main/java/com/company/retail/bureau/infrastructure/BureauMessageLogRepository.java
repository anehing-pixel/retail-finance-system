package com.company.retail.bureau.infrastructure;

import com.company.retail.bureau.mapper.BureauMessageLogMapper;

/**
 * 机构报文留痕 MyBatis 仓储（integration.bureau_message_logs，V11 增量表，append-only）。
 *
 * <p>报文原文只在留痕表与排障查询中出现；不进事件 payload、不进应用日志（PII-4）。</p>
 *
 * <p><b>append-only</b>：仓储只暴露追加与计数，Mapper 层也不提供 UPDATE/DELETE，
 * 报文留痕不可改写（R05 X-11 / ADR-RF-005 §2）。</p>
 *
 * <p>本实现与端口 {@code com.company.retail.bureau.application.BureauMessageLogRepository} 同名，
 * 故 implements 子句用全限定名；对外方法签名与语义一字未改。</p>
 */
public class BureauMessageLogRepository
        implements com.company.retail.bureau.application.BureauMessageLogRepository {

    private final BureauMessageLogMapper mapper;

    public BureauMessageLogRepository(BureauMessageLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void append(String direction, String bureau, String bizRef, String messageText, String traceId) {
        mapper.append(direction, bureau, bizRef, messageText, traceId);
    }

    @Override
    public long countByBizRef(String bizRef) {
        Long count = mapper.countByBizRef(bizRef);
        return count == null ? 0L : count;
    }
}
