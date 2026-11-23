package com.company.retail.servicing.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * outbox 事件状态只读跟踪 MyBatis Mapper（core.outbox_events，横切表只读）。
 */
public interface OutboxTrackingMapper {

    @Select("SELECT status FROM core.outbox_events "
            + "WHERE event_type = #{eventType} AND aggregate_key = #{aggregateKey} "
            + "ORDER BY event_id DESC LIMIT 1")
    List<Map<String, Object>> latestEventStatus(@Param("eventType") String eventType,
                                                 @Param("aggregateKey") String aggregateKey);
}
