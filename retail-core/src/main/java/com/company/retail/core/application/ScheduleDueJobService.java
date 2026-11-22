package com.company.retail.core.application;

import com.company.retail.core.persistence.JdbcRepaymentPlanRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;

/**
 * 到期批量（W6：schedule_items 到期日判定，R04 §3.6 / R05 §9 入金核销批处理补跑幂等）。
 *
 * <p>判定口径（{@code ScheduleItemStatus}）：OPEN 且 due_date ≤ bizDate → DUE；
 * DUE 且 due_date &lt; bizDate → OVERDUE（逾期按自然日）。批处理可重跑（幂等更新），
 * (job_name, biz_date) 唯一落 {@code core.batch_runs} 留痕。</p>
 */
@Service
public class ScheduleDueJobService {

    public static final String JOB_NAME = "schedule-due-rollup";

    private final JdbcRepaymentPlanRepository planRepository;
    private final JdbcTemplate jdbc;

    public ScheduleDueJobService(JdbcRepaymentPlanRepository planRepository, JdbcTemplate jdbc) {
        this.planRepository = planRepository;
        this.jdbc = jdbc;
    }

    /** 触发到期批量（POST /api/jobs/schedule-due-rollup，权限点 JOB_TRIGGER）。 */
    @Transactional
    public Map<String, Object> rollUp(LocalDate bizDate, String traceId) {
        int dueCount = planRepository.rollDue(bizDate);
        int overdueCount = planRepository.rollOverdue(bizDate);

        // 批次留痕（uk_batch_job_date；同日重跑幂等——ON CONFLICT 不改历史）
        jdbc.update("INSERT INTO core.batch_runs (job_name, biz_date, status, finished_at, trace_id) "
                        + "VALUES (?, ?, 'DONE', now(), ?) ON CONFLICT (job_name, biz_date) DO NOTHING",
                JOB_NAME, java.sql.Date.valueOf(bizDate), traceId);

        return Map.of("jobName", JOB_NAME, "bizDate", bizDate.toString(),
                "dueCount", dueCount, "overdueCount", overdueCount);
    }
}
