package com.company.retail.servicing.schedule;

import com.company.retail.servicing.application.DpdBatchAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/**
 * dpd-snapshot 日批调度器（W9-C：DPD 快照 @Scheduled 接通）。
 *
 * <p><b>开关与配置（retail.jobs.*，与 retail-core 同一命名风格，各自 application.yml）</b>：</p>
 * <ul>
 *   <li>{@code retail.jobs.schedule-enabled} —— 默认 false（不启用不调度，
 *       手工 {@code POST /api/jobs/dpd-snapshot} 路径不受影响）；</li>
 *   <li>{@code retail.jobs.schedule-cron} —— 默认 {@code 0 30 3 * * *}（JST 环境 03:30 日批，
 *       晚于 retail-core 到期滚转，DPD 口径用当日已滚转状态）。</li>
 * </ul>
 *
 * <p><b>护栏</b>：{@link BatchRunGuard} 抢占（并行防重 / 同日 SUCCESS 不重跑 / FAILED 重试 /
 * 卡死接管）；批执行复用既有 {@link DpdBatchAppService#run}（幂等 + batch_runs 留痕 +
 * 失败自标 FAILED）；调度层再把异常 catch 住，<b>不中断调度线程</b>。</p>
 */
@Component
@ConditionalOnProperty(name = "retail.jobs.schedule-enabled", havingValue = "true")
public class DpdSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(DpdSnapshotScheduler.class);

    private final DpdBatchAppService dpdBatchAppService;
    private final BatchRunGuard guard;
    private final int staleMinutes;

    public DpdSnapshotScheduler(DpdBatchAppService dpdBatchAppService, BatchRunGuard guard,
            @Value("${retail.jobs.stale-minutes:30}") int staleMinutes) {
        this.dpdBatchAppService = dpdBatchAppService;
        this.guard = guard;
        this.staleMinutes = staleMinutes <= 0 ? 30 : staleMinutes;
    }

    @Scheduled(cron = "${retail.jobs.schedule-cron:0 30 3 * * *}")
    public void runScheduled() {
        LocalDate bizDate = LocalDate.now();
        String traceId = "SCHED-" + UUID.randomUUID().toString().substring(0, 8);
        if (!guard.tryClaim(DpdBatchAppService.JOB_NAME, bizDate, traceId, staleMinutes)) {
            log.info("dpd-snapshot 日批跳过：同日已有 SUCCESS 或活跃 RUNNING bizDate={}", bizDate);
            return;
        }
        try {
            // actor=SYSTEM（B-9：调度上下文无验签请求，按 SYSTEM 处理）
            dpdBatchAppService.run(bizDate, "SYSTEM", traceId);
            log.info("dpd-snapshot 日批完成 bizDate={} traceId={}", bizDate, traceId);
        } catch (RuntimeException e) {
            // 护栏：执行异常不中断调度线程（run 内部已标 FAILED 且事务回滚时由 guard 兜底留痕）
            log.error("dpd-snapshot 日批失败 bizDate={} traceId={}", bizDate, traceId, e);
            guard.markFinished(DpdBatchAppService.JOB_NAME, bizDate, "FAILED");
        }
    }
}
