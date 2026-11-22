package com.company.retail.servicing.application;

import java.util.Optional;

/**
 * outbox 事件状态只读跟踪端口（表 core.outbox_events，横切表只读）。
 *
 * <p>报送触发的「状态跟踪」口径：servicing 不跨域读 integration.credit_bureau_reports
 * （bureau-service 真相源），以 outbox 事件（DPD_CHANGED / aggregate_key=loanId）的
 * PENDING/SENT 状态作为触发是否已送达的跟踪锚点。</p>
 */
public interface OutboxTrackingPort {

    /** 该 (event_type, aggregate_key) 最近一条事件的投递状态；无事件为空。 */
    Optional<String> latestEventStatus(String eventType, String aggregateKey);
}
