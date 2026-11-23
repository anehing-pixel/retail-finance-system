package com.company.retail.core.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 批处理留痕 MyBatis Mapper（表 core.batch_runs，uk_batch_job_date 唯一约束）。
 */
public interface BatchRunMapper {

    /** 抢占锁占位（ON CONFLICT DO UPDATE ... WHERE：FAILED 可重试 / 活跃 RUNNING 或同日 SUCCESS 拒绝）。 */
    @Insert("INSERT INTO core.batch_runs (job_name, biz_date, status, trace_id) "
            + "VALUES (#{jobName}, #{bizDate}, 'RUNNING', #{traceId}) "
            + "ON CONFLICT (job_name, biz_date) DO UPDATE "
            + "SET status = 'RUNNING', started_at = now(), finished_at = NULL, "
            + "    trace_id = EXCLUDED.trace_id "
            + "WHERE core.batch_runs.status = 'FAILED' "
            + "   OR (core.batch_runs.status = 'RUNNING' "
            + "       AND core.batch_runs.started_at < now() - make_interval(mins => #{staleMinutes}))")
    int tryClaim(@Param("jobName") String jobName, @Param("bizDate") LocalDate bizDate,
                 @Param("traceId") String traceId, @Param("staleMinutes") int staleMinutes);

    /** 落终态（upsert：异常路径下 RUNNING 行可能已随事务回滚，insert 兜底留痕）。 */
    @Insert("INSERT INTO core.batch_runs (job_name, biz_date, status, finished_at) "
            + "VALUES (#{jobName}, #{bizDate}, #{status}, now()) "
            + "ON CONFLICT (job_name, biz_date) DO UPDATE "
            + "SET status = EXCLUDED.status, finished_at = now()")
    int markFinished(@Param("jobName") String jobName, @Param("bizDate") LocalDate bizDate,
                     @Param("status") String status);

    /** 到期批量留痕（同日重跑幂等——ON CONFLICT DO NOTHING 不改历史）。 */
    @Insert("INSERT INTO core.batch_runs (job_name, biz_date, status, finished_at, trace_id) "
            + "VALUES (#{jobName}, #{bizDate}, 'DONE', now(), #{traceId}) "
            + "ON CONFLICT (job_name, biz_date) DO NOTHING")
    int recordDone(@Param("jobName") String jobName, @Param("bizDate") LocalDate bizDate,
                   @Param("traceId") String traceId);
}
