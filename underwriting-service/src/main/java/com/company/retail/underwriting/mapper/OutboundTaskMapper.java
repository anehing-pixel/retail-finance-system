package com.company.retail.underwriting.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 出站任务 Mapper（P0-7；表 {@code integration.outbound_tasks}）。
 *
 * <p>状态机：PENDING → IN_PROGRESS → DONE / RETRY → … / DEAD；
 * 认领用 {@code FOR UPDATE SKIP LOCKED}（多实例安全），所有状态写带 version 乐观锁。
 * 不新增 Flyway 迁移：复用 V9 既有列。</p>
 */
public interface OutboundTaskMapper {

    /** 落一条 PENDING（同 task_type + payload_ref 已有活跃任务时不重复插入）。 */
    @Insert("INSERT INTO integration.outbound_tasks "
            + "(partner_id, task_type, payload_ref, status, retry_count, next_retry_at, trace_id) "
            + "SELECT 0, #{taskType}, #{payloadRef}, 'PENDING', 0, "
            + "       now() + make_interval(secs => #{nextRetrySeconds}), #{traceId} "
            + "WHERE NOT EXISTS ("
            + "  SELECT 1 FROM integration.outbound_tasks "
            + "  WHERE task_type = #{taskType} AND payload_ref = #{payloadRef} "
            + "    AND status IN ('PENDING','RETRY','IN_PROGRESS'))")
    int insertPending(@Param("taskType") String taskType,
                      @Param("payloadRef") String payloadRef,
                      @Param("traceId") String traceId,
                      @Param("nextRetrySeconds") long nextRetrySeconds);

    /** 回收租约过期的 IN_PROGRESS；达到上限直接 DEAD，否则回 RETRY。 */
    @Update("UPDATE integration.outbound_tasks "
            + "SET retry_count = retry_count + 1, "
            + "    status = CASE WHEN retry_count + 1 >= #{maxRetries} THEN 'DEAD' ELSE 'RETRY' END, "
            + "    next_retry_at = CASE WHEN retry_count + 1 >= #{maxRetries} THEN NULL ELSE now() END, "
            + "    updated_at = now(), version = version + 1 "
            + "WHERE task_id IN ("
            + "  SELECT task_id FROM integration.outbound_tasks "
            + "  WHERE status = 'IN_PROGRESS' "
            + "    AND updated_at < now() - make_interval(secs => #{leaseSeconds}) "
            + "  ORDER BY task_id LIMIT #{limit} FOR UPDATE SKIP LOCKED)")
    int reapStale(@Param("leaseSeconds") long leaseSeconds,
                  @Param("maxRetries") int maxRetries,
                  @Param("limit") int limit);

    /** 原子认领到期任务（PENDING/RETRY 且 next_retry_at 已到），返回认领后的行（含新 version）。 */
    @Select("WITH claimed AS ("
            + "  UPDATE integration.outbound_tasks "
            + "  SET status = 'IN_PROGRESS', updated_at = now(), version = version + 1 "
            + "  WHERE task_id IN ("
            + "    SELECT task_id FROM integration.outbound_tasks "
            + "    WHERE status IN ('PENDING','RETRY') "
            + "      AND (next_retry_at IS NULL OR next_retry_at <= now()) "
            + "    ORDER BY next_retry_at NULLS FIRST, task_id "
            + "    LIMIT #{limit} FOR UPDATE SKIP LOCKED) "
            + "  RETURNING task_id, task_type, payload_ref, retry_count, trace_id, version) "
            + "SELECT * FROM claimed")
    List<Map<String, Object>> claimDue(@Param("limit") int limit);

    @Update("UPDATE integration.outbound_tasks "
            + "SET status = 'DONE', next_retry_at = NULL, updated_at = now(), version = version + 1 "
            + "WHERE task_id = #{taskId} AND version = #{version} AND status = 'IN_PROGRESS'")
    int markDone(@Param("taskId") long taskId, @Param("version") int version);

    @Update("UPDATE integration.outbound_tasks "
            + "SET status = 'RETRY', retry_count = #{retryCount}, "
            + "    next_retry_at = now() + make_interval(secs => #{delaySeconds}), "
            + "    updated_at = now(), version = version + 1 "
            + "WHERE task_id = #{taskId} AND version = #{version} AND status = 'IN_PROGRESS'")
    int markRetry(@Param("taskId") long taskId, @Param("version") int version,
                  @Param("retryCount") int retryCount, @Param("delaySeconds") long delaySeconds);

    @Update("UPDATE integration.outbound_tasks "
            + "SET status = 'DEAD', next_retry_at = NULL, updated_at = now(), version = version + 1 "
            + "WHERE task_id = #{taskId} AND version = #{version} AND status = 'IN_PROGRESS'")
    int markDead(@Param("taskId") long taskId, @Param("version") int version);

    @Select("SELECT task_id, task_type, payload_ref, status, retry_count, next_retry_at, "
            + "       trace_id, version FROM integration.outbound_tasks WHERE task_id = #{taskId}")
    Map<String, Object> findById(@Param("taskId") long taskId);

    /** 人工重触发：DEAD → PENDING（重置重试计数）。 */
    @Update("UPDATE integration.outbound_tasks "
            + "SET status = 'PENDING', retry_count = 0, next_retry_at = now(), "
            + "    updated_at = now(), version = version + 1 "
            + "WHERE task_id = #{taskId} AND status = 'DEAD'")
    int resetToPending(@Param("taskId") long taskId);
}
