package com.company.retail.servicing.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 批处理留痕 MyBatis Mapper（表 core.batch_runs；uk_batch_job_date 同日重跑复用同一行，A28）。
 *
 * <p>与 retail-core 同构（W9-C 约定：各服务模块各持一份，不建共享配置类）。</p>
 */
public interface BatchRunMapper {

    /** 开始一轮：同 job+bizDate 重跑 → 复位 RUNNING，不留第二行。 */
    @Insert("INSERT INTO core.batch_runs (job_name, biz_date, status, trace_id) "
            + "VALUES (#{jobName}, #{bizDate}, 'RUNNING', #{traceId}) "
            + "ON CONFLICT (job_name, biz_date) DO UPDATE "
            + "SET status = 'RUNNING', started_at = now(), finished_at = NULL, "
            + "    trace_id = EXCLUDED.trace_id")
    int start(@Param("jobName") String jobName,
              @Param("bizDate") LocalDate bizDate,
              @Param("traceId") String traceId);

    /** 结束（SUCCESS / FAILED + finished_at）。 */
    @Update("UPDATE core.batch_runs SET status = #{status}, finished_at = now() "
            + "WHERE job_name = #{jobName} AND biz_date = #{bizDate}")
    int finish(@Param("jobName") String jobName,
               @Param("bizDate") LocalDate bizDate,
               @Param("status") String status);

    /**
     * 抢占批次：一次原子操作完成「无行则占位 / FAILED 可重试 / 活跃 RUNNING 或同日 SUCCESS 拒绝」。
     */
    @Insert("INSERT INTO core.batch_runs (job_name, biz_date, status, trace_id) "
            + "VALUES (#{jobName}, #{bizDate}, 'RUNNING', #{traceId}) "
            + "ON CONFLICT (job_name, biz_date) DO UPDATE "
            + "SET status = 'RUNNING', started_at = now(), finished_at = NULL, "
            + "    trace_id = EXCLUDED.trace_id "
            + "WHERE core.batch_runs.status = 'FAILED' "
            + "   OR (core.batch_runs.status = 'RUNNING' "
            + "       AND core.batch_runs.started_at < now() - make_interval(mins => #{staleMinutes}))")
    int tryClaim(@Param("jobName") String jobName,
                 @Param("bizDate") LocalDate bizDate,
                 @Param("traceId") String traceId,
                 @Param("staleMinutes") int staleMinutes);

    /** 落终态（upsert：异常路径下 RUNNING 行可能已随事务回滚，insert 兜底留痕）。 */
    @Insert("INSERT INTO core.batch_runs (job_name, biz_date, status, finished_at) "
            + "VALUES (#{jobName}, #{bizDate}, #{status}, now()) "
            + "ON CONFLICT (job_name, biz_date) DO UPDATE "
            + "SET status = EXCLUDED.status, finished_at = now()")
    int markFinished(@Param("jobName") String jobName,
                     @Param("bizDate") LocalDate bizDate,
                     @Param("status") String status);
}
