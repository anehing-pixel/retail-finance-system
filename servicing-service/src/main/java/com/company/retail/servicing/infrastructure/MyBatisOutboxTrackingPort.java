package com.company.retail.servicing.infrastructure;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.servicing.application.OutboxTrackingPort;
import com.company.retail.servicing.mapper.OutboxTrackingMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * outbox 事件状态只读跟踪 MyBatis 实现（core.outbox_events，横切表只读）。
 */
public class MyBatisOutboxTrackingPort implements OutboxTrackingPort {

    private final OutboxTrackingMapper mapper;

    public MyBatisOutboxTrackingPort(OutboxTrackingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<String> latestEventStatus(String eventType, String aggregateKey) {
        List<Map<String, Object>> rows = mapper.latestEventStatus(eventType, aggregateKey);
        return rows.stream().findFirst()
                .map(r -> RowValues.asString(r, "status"));
    }
}
