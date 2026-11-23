package com.company.retail.infrastructure.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * MQ 密文信封消费幂等表 Mapper（表 integration.mq_envelope_inbox，V20）。
 *
 * <p>幂等主键 {@code (envelope_id, consumer)}：与直投 outbox 的 {@code (event_id, consumer)}
 * 同构（R09 §6 幂等三层之一：数据库唯一索引兜底）。</p>
 *
 * <p>SQL 语义与 RfEventDispatcher 对 event_consumptions 的用法完全一致：
 * 占位插入用 {@code ON CONFLICT DO NOTHING}——PostgreSQL 事务内一旦触发唯一键错误，
 * 整笔事务即被置为 aborted，无法继续读库，故不采用「捕获唯一键异常再重读」。</p>
 */
public interface MqEnvelopeInboxMapper {

    /** 占位插入（幂等主键 envelope_id + consumer）。 */
    @Insert("INSERT INTO integration.mq_envelope_inbox (envelope_id, topic, consumer, status, attempts) "
            + "VALUES (#{envelopeId}, #{topic}, #{consumer}, 'PENDING', 0) "
            + "ON CONFLICT (envelope_id, consumer) DO NOTHING")
    int insertIfAbsent(@Param("envelopeId") String envelopeId,
                       @Param("topic") String topic,
                       @Param("consumer") String consumer);

    /** 读消费状态与重试次数。 */
    @Select("SELECT status, attempts FROM integration.mq_envelope_inbox "
            + "WHERE envelope_id = #{envelopeId} AND consumer = #{consumer}")
    List<Map<String, Object>> find(@Param("envelopeId") String envelopeId,
                                   @Param("consumer") String consumer);

    /** 消费成功。 */
    @Update("UPDATE integration.mq_envelope_inbox SET status = 'DONE', attempts = #{next}, "
            + "last_error = NULL, updated_at = now() "
            + "WHERE envelope_id = #{envelopeId} AND consumer = #{consumer}")
    int markDone(@Param("envelopeId") String envelopeId,
                 @Param("consumer") String consumer,
                 @Param("next") int next);

    /** 消费失败（FAILED）。 */
    @Update("UPDATE integration.mq_envelope_inbox SET status = 'FAILED', attempts = #{next}, "
            + "last_error = #{err}, updated_at = now() "
            + "WHERE envelope_id = #{envelopeId} AND consumer = #{consumer}")
    int markFailed(@Param("envelopeId") String envelopeId,
                   @Param("consumer") String consumer,
                   @Param("next") int next,
                   @Param("err") String err);

    /** 置 DEAD（已达重试上限，不再累加 attempts）。 */
    @Update("UPDATE integration.mq_envelope_inbox SET status = 'DEAD', last_error = #{err}, "
            + "updated_at = now() WHERE envelope_id = #{envelopeId} AND consumer = #{consumer}")
    int markDead(@Param("envelopeId") String envelopeId,
                 @Param("consumer") String consumer,
                 @Param("err") String err);
}
