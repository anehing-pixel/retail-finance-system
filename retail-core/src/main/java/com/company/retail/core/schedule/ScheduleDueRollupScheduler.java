package com.company.retail.core.schedule;

import com.company.retail.core.application.ScheduleDueJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/**
 * schedule-due-rollup 日批调度器（W9-C：到期滚转 @Scheduled 接通）。
 *
 * <p><b>开关与配置（retail.jobs.*，与 servicing 同一命名风格，各服务各自 application.yml）</b>：</p>
 * <ul>
 *   <li>{@code retail.jobs.schedule-enabled} —— 默认 false（不启用不调度，手工
 *       {@code POST /api/jobs/schedule-due-rollup} 路径不受影响）；</li>
 *   <li>{@code retail.jobs.schedule-cron} —— 默认 {@code 0 0 3 * * *}（JST 环境 03:00 日批）。</li>
 * </ul>
 *
 * <p><b>护栏</b>：{@link BatchRunGuard} 抢占（并行防重 / 同日 SUCCESS 不重跑 / FAILED 重试 /
 * 卡死接管）；执行异常 catch 住落 FAILED 留痕，<b>不中断调度线程</b>；
 * 批执行复用既有 {@link ScheduleDueJobService#rollUp}（幂等 + batch_runs 留痕）。</p>
 */
@Component
@ConditionalOnProperty(name = "retail.jobs.schedule-enabled", havingValue = "true")
public class ScheduleDueRollupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduleDueRollupScheduler.class);

    /** RUNNING 行视为卡死的分钟数（调度实例崩溃后接管窗口）。 */
    static final int STALE_MINUTES = 30;

    private final ScheduleDueJobService jobService;
    private final BatchRunGuard guard;
    private final int staleMinutes;

    public ScheduleDueRollupScheduler(ScheduleDueJobService jobService, BatchRunGuard guard,
            @Value("${retail.jobs.stale-minutes:30}") int staleMinutes) {
        this.jobService = jobService;
        this.guard = guard;
        this.staleMinutes = staleMinutes <= 0 ? STALE_MINUTES : staleMinutes;
    }

    @Scheduled(cron = "${retail.jobs.schedule-cron:0 0 3 * * *}")
    public void runScheduled() {
        LocalDate bizDate = LocalDate.now();
        String traceId = "SCHED-" + UUID.randomUUID().toString().substring(0, 8);
        if (!guard.tryClaim(ScheduleDueJobService.JOB_NAME, bizDate, traceId, staleMinutes)) {
            log.info("schedule-due-rollup 日批跳过：同日已有 SUCCESS 或活跃 RUNNING bizDate={}", bizDate);
            return;
        }
        try {
            jobService.rollUp(bizDate, traceId);
            guard.markFinished(ScheduleDueJobService.JOB_NAME, bizDate, "DONE");
            log.info("schedule-due-rollup 日批完成 bizDate={} traceId={}", bizDate, traceId);
        } catch (RuntimeException e) {
            // 护栏：执行异常不中断调度线程（下一 cron 触发按 FAILED 可重试）
            log.error("schedule-due-rollup 日批失败 bizDate={} traceId={}", bizDate, traceId, e);
            guard.markFinished(ScheduleDueJobService.JOB_NAME, bizDate, "FAILED");
        }
    }
}
