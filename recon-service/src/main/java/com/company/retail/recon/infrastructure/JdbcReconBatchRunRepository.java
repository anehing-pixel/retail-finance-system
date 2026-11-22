package com.company.retail.recon.infrastructure;

import com.company.retail.recon.application.ReconBatchRunRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 批处理留痕 JDBC 实现（core.batch_runs；uk_batch_job_date 同日重跑复用同一行，A28）。
 */
public class JdbcReconBatchRunRepository implements ReconBatchRunRepository {

    private final JdbcTemplate jdbc;

    public JdbcReconBatchRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void start(String jobName, String bizDate, String traceId) {
        jdbc.update("INSERT INTO core.batch_runs (job_name, biz_date, status, trace_id) "
                        + "VALUES (?, ?, 'RUNNING', ?) "
                        + "ON CONFLICT (job_name, biz_date) DO UPDATE "
                        + "SET status = 'RUNNING', started_at = now(), finished_at = NULL, "
                        + "    trace_id = EXCLUDED.trace_id",
                jobName, java.time.LocalDate.parse(bizDate), traceId);
    }

    @Override
    public void finish(String jobName, String bizDate, String status) {
        jdbc.update("UPDATE core.batch_runs SET status = ?, finished_at = now() "
                        + "WHERE job_name = ? AND biz_date = ?",
                status, jobName, java.time.LocalDate.parse(bizDate));
    }

    @Override
    public List<RunRow> list(String jobNameOrNull) {
        StringBuilder sql = new StringBuilder(
                "SELECT run_id, job_name, biz_date, status FROM core.batch_runs");
        List<Object> args = new ArrayList<>();
        if (jobNameOrNull != null && !jobNameOrNull.isBlank()) {
            sql.append(" WHERE job_name = ?");
            args.add(jobNameOrNull);
        }
        sql.append(" ORDER BY run_id DESC LIMIT 100");
        return jdbc.queryForList(sql.toString(), args.toArray()).stream()
                .map(r -> new RunRow(((Number) r.get("run_id")).longValue(),
                        (String) r.get("job_name"),
                        String.valueOf(r.get("biz_date")),
                        (String) r.get("status")))
                .toList();
    }
}
