package com.company.retail.infrastructure.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Outbox 事件与消费游标的 MyBatis Mapper（表在 core schema，R06 §4.8）。
 *
 * <p>SQL 与改造前的 JdbcTemplate 版逐字一致（仅占位符由 ?/:name 改为 MyBatis 的 #{name}），
 * 语义不变：去重插入、at-least-once 投递、消费幂等主键、失败计数与 DEAD、游标推进。</p>
 *
 * <p><b>游标表名</b>：V1 建的表是 {@code core.consumer_offsets}（consumer / last_event_id /
 * updated_at），本 Mapper 的三处游标 SQL 必须写这个名字——写错表名会让
 * {@code insertOffsetIfAbsent} 抛 BadSqlGrammarException，轮询整条停摆
 * （事件全部滞留 PENDING、状态机推进不了），且只在日志里留一行 WARN，极难发现。</p>
 *
 * <p>ADR-RF-014 新增中继查询：只取「仍为 PENDING 且未中继」的事件，relayed_at 为空即未中继过。</p>
 */
public interface OutboxMapper {

    /** 事件入箱（去重：同一 event_type + aggregate_key 已有 PENDING/SENT 则不插入）。 */
    @Insert("INSERT INTO core.outbox_events (event_type, aggregate_key, payload, status) "
            + "SELECT #{eventType}, #{aggregateKey}, #{payload}, 'PENDING' "
            + "WHERE NOT EXISTS (SELECT 1 FROM core.outbox_events "
            + "                  WHERE event_type = #{eventType} AND aggregate_key = #{aggregateKey} "
            + "                    AND status IN ('PENDING', 'SENT'))")
    int insertEventIfAbsent(@Param("eventType") String eventType,
                             @Param("aggregateKey") String aggregateKey,
                             @Param("payload") String payload);

    /** 游标之后、指定事件类型的待投递事件（按 event_id 升序，单批上限 limit）。 */
    @Select({"<script>",
            "SELECT event_id, event_type, aggregate_key, payload, created_at FROM core.outbox_events ",
            "WHERE event_id &gt; #{offset} AND event_type IN ",
            "<foreach item='t' collection='types' open='(' separator=',' close=')'>#{t}</foreach> ",
            "ORDER BY event_id LIMIT #{limit}",
            "</script>"})
    List<Map<String, Object>> selectEventsAfter(@Param("offset") long offset,
                                                @Param("types") Collection<String> types,
                                                @Param("limit") int limit);

    /** 标记事件已发送。 */
    @Update("UPDATE core.outbox_events SET status = 'SENT', sent_at = now() "
            + "WHERE event_id = #{eventId} AND status = 'PENDING'")
    int markSent(@Param("eventId") long eventId);

    /** 待中继事件（ADR-RF-014）：PENDING 且 relayed_at 为空。 */
    @Select("SELECT event_id, event_type, aggregate_key, payload, created_at FROM core.outbox_events "
            + "WHERE status = 'PENDING' AND relayed_at IS NULL ORDER BY event_id LIMIT #{limit}")
    List<Map<String, Object>> selectUnrelayedEvents(@Param("limit") int limit);

    /** 标记事件已中继（只标记一次：relayed_at 为空才更新，投递失败不标记 → 下轮重试）。 */
    @Update("UPDATE core.outbox_events SET relayed_at = now() "
            + "WHERE event_id = #{eventId} AND relayed_at IS NULL")
    int markRelayed(@Param("eventId") long eventId);

    /** 消费记录占位（幂等主键 event_id + consumer）。 */
    @Insert("INSERT INTO core.event_consumptions (event_id, consumer, status, attempts) "
            + "VALUES (#{eventId}, #{consumer}, 'PENDING', 0) "
            + "ON CONFLICT (event_id, consumer) DO NOTHING")
    int insertConsumptionIfAbsent(@Param("eventId") long eventId, @Param("consumer") String consumer);

    /** 读消费状态与重试次数。 */
    @Select("SELECT status, attempts FROM core.event_consumptions "
            + "WHERE event_id = #{eventId} AND consumer = #{consumer}")
    List<Map<String, Object>> findConsumption(@Param("eventId") long eventId,
                                               @Param("consumer") String consumer);

    /** 消费成功。 */
    @Update("UPDATE core.event_consumptions SET status = 'DONE', attempts = #{next}, "
            + "last_error = NULL, updated_at = now() "
            + "WHERE event_id = #{eventId} AND consumer = #{consumer}")
    int markConsumptionDone(@Param("eventId") long eventId, @Param("consumer") String consumer,
                            @Param("next") int next);

    /** 消费失败（FAILED）。记录尝试次数与错误摘要。 */
    @Update("UPDATE core.event_consumptions SET status = 'FAILED', attempts = #{next}, "
            + "last_error = #{err}, updated_at = now() "
            + "WHERE event_id = #{eventId} AND consumer = #{consumer}")
    int markConsumptionFailed(@Param("eventId") long eventId, @Param("consumer") String consumer,
                               @Param("next") int next, @Param("err") String err);

    /** 置 DEAD（已达重试上限；不再累加 attempts）。 */
    @Update("UPDATE core.event_consumptions SET status = 'DEAD', last_error = #{err}, "
            + "updated_at = now() WHERE event_id = #{eventId} AND consumer = #{consumer}")
    int markConsumptionDead(@Param("eventId") long eventId, @Param("consumer") String consumer,
                            @Param("err") String err);

    /** 游标占位。 */
    @Insert("INSERT INTO core.consumer_offsets (consumer, last_event_id) VALUES (#{consumer}, 0) "
            + "ON CONFLICT (consumer) DO NOTHING")
    int insertOffsetIfAbsent(@Param("consumer") String consumer);

    /** 读游标。 */
    @Select("SELECT last_event_id FROM core.consumer_offsets WHERE consumer = #{consumer}")
    Long findOffset(@Param("consumer") String consumer);

    /** 推进游标。 */
    @Update("UPDATE core.consumer_offsets SET last_event_id = #{eventId}, updated_at = now() "
            + "WHERE consumer = #{consumer}")
    int writeOffset(@Param("consumer") String consumer, @Param("eventId") long eventId);
}
