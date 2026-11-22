package com.company.retail.core.schedule;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;

/**
 * 批处理安全护栏：基于 {@code core.batch_runs} 的单条 SQL 抢占锁（W9-C，不引入新中间件）。
 *
 * <p>{@code uk_batch_job_date (job_name, biz_date)} 唯一约束 + {@code ON CONFLICT DO UPDATE ... WHERE}：
 * 一次原子操作完成「无行则占位 / FAILED 可重试 / 活跃 RUNNING 或同日 SUCCESS 拒绝」。</p>
 *
 * <ul>
 *   <li>并行防重：第二实例抢占返回 false，不重复执行；</li>
 *   <li>卡死自愈：RUNNING 超 {@code staleMinutes} 视为上次实例已死，允许接管；</li>
 *   <li>留痕：占位即 RUNNING 行，结束由调度器 upsert 终态（DONE / FAILED）。</li>
 * </ul>
 */
@Component
public class BatchRunGuard {

    private final JdbcTemplate jdbc;

    public BatchRunGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试占用 (jobName, bizDate) 批次。
     *
     * @return true = 抢占成功（调用方执行作业并负责落终态）；false = 已有 SUCCESS 或活跃 RUNNING，跳过
     */
    public boolean tryClaim(String jobName, LocalDate bizDate, String traceId, int staleMinutes) {
        int claimed = jdbc.update(
                "INSERT INTO core.batch_runs (job_name, biz_date, status, trace_id) "
                        + "VALUES (?, ?, 'RUNNING', ?) "
                        + "ON CONFLICT (job_name, biz_date) DO UPDATE "
                        + "SET status = 'RUNNING', started_at = now(), finished_at = NULL, "
                        + "    trace_id = EXCLUDED.trace_id "
                        + "WHERE core.batch_runs.status = 'FAILED' "
                        + "   OR (core.batch_runs.status = 'RUNNING' "
                        + "       AND core.batch_runs.started_at < now() - make_interval(mins => ?))",
                jobName, Date.valueOf(bizDate), traceId, staleMinutes);
        return claimed > 0;
    }

    /** 落终态（upsert：异常路径下 RUNNING 行可能已随事务回滚，insert 兜底留痕）。 */
    public void markFinished(String jobName, LocalDate bizDate, String status) {
        jdbc.update(
                "INSERT INTO core.batch_runs (job_name, biz_date, status, finished_at) "
                        + "VALUES (?, ?, ?, now()) "
                        + "ON CONFLICT (job_name, biz_date) DO UPDATE "
                        + "SET status = EXCLUDED.status, finished_at = now()",
                jobName, Date.valueOf(bizDate), status);
    }
}
