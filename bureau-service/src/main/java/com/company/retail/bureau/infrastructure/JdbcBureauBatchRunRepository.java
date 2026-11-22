package com.company.retail.bureau.infrastructure;

import com.company.retail.bureau.application.BureauBatchRunPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

/**
 * 批处理留痕 JDBC 仓储（core.batch_runs；uk_batch_job_date 同日重跑复用同一行）。
 */
public class JdbcBureauBatchRunRepository implements BureauBatchRunPort {

    private final JdbcTemplate jdbc;

    public JdbcBureauBatchRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void start(String jobName, LocalDate bizDate, String traceId) {
        jdbc.update("INSERT INTO core.batch_runs (job_name, biz_date, status, trace_id) "
                        + "VALUES (?, ?, 'RUNNING', ?) "
                        + "ON CONFLICT (job_name, biz_date) DO UPDATE "
                        + "SET status = 'RUNNING', started_at = now(), finished_at = NULL, "
                        + "    trace_id = EXCLUDED.trace_id",
                jobName, bizDate, traceId);
    }

    @Override
    public void finish(String jobName, LocalDate bizDate, String status) {
        jdbc.update("UPDATE core.batch_runs SET status = ?, finished_at = now() "
                        + "WHERE job_name = ? AND biz_date = ?",
                status, jobName, bizDate);
    }
}
