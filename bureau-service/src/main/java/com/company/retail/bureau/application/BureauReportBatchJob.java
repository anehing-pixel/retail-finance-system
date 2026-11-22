package com.company.retail.bureau.application;

import com.company.platform.calendar.BusinessCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 报送日批（C-05：报送周期 T+1；R05 §228「征信报送 T+1」的兜底执行器）。
 *
 * <p>职责：事件消费之外的兜底通道——扫描 core.bureau_report_triggers 中
 * status=REGISTERED 且 target_biz_date 已到期的触发，逐条经
 * {@link BureauReportExecutionService#executeTrigger}（与消费者<b>同一服务方法</b>）执行；
 * 幂等由 uk_report_loan_trigger 保证（重跑不重复报送）。</p>
 *
 * <ul>
 *   <li>开关：retail.bureau.report-schedule-enabled（<b>默认 false</b>，关=完全不跑）；</li>
 *   <li>cron：retail.bureau.report-cron（默认每日 08:00 JST，可配）；</li>
 *   <li>营业日判定：{@link BusinessCalendar#isBusinessDay}——非营业日 SKIPPED 留痕；</li>
 *   <li>batch_runs 留痕：job_name=BUREAU_REPORT，uk_batch_job_date 同日重跑复用同一行。</li>
 * </ul>
 */
public class BureauReportBatchJob {

    public static final String JOB_NAME = "BUREAU_REPORT";

    private static final Logger log = LoggerFactory.getLogger(BureauReportBatchJob.class);

    private final BureauTriggerPort triggerPort;
    private final BureauReportExecutionService executionService;
    private final BureauBatchRunPort batchRunPort;
    private final BusinessCalendar businessCalendar;
    private final boolean scheduleEnabled;

    public BureauReportBatchJob(BureauTriggerPort triggerPort,
                                BureauReportExecutionService executionService,
                                BureauBatchRunPort batchRunPort,
                                BusinessCalendar businessCalendar,
                                boolean scheduleEnabled) {
        this.triggerPort = triggerPort;
        this.executionService = executionService;
        this.batchRunPort = batchRunPort;
        this.businessCalendar = businessCalendar;
        this.scheduleEnabled = scheduleEnabled;
    }

    /** 调度入口（cron 可配，JST 时区）。 */
    @Scheduled(cron = "${retail.bureau.report-cron:0 0 8 * * *}", zone = "Asia/Tokyo")
    public void run() {
        runBatch(LocalDate.now(ZoneId.of("Asia/Tokyo")));
    }

    /** 执行一轮报送日批（可测入口；开关关/非营业日/无到期触发的语义见类注释）。 */
    public void runBatch(LocalDate today) {
        // 开关关 = 不跑（默认 false）：不写 batch_runs、不执行任何触发
        if (!scheduleEnabled) {
            log.debug("报送日批未启用（retail.bureau.report-schedule-enabled=false），跳过");
            return;
        }
        String traceId = "batch-" + UUID.randomUUID();
        batchRunPort.start(JOB_NAME, today, traceId);
        try {
            if (!businessCalendar.isBusinessDay(today)) {
                // T+1 营业日口径：非营业日不报送，留 SKIPPED 痕（可审计）
                batchRunPort.finish(JOB_NAME, today, "SKIPPED");
                log.info("报送日批跳过（非营业日）today={} traceId={}", today, traceId);
                return;
            }
            var due = triggerPort.findDue(today);
            int sent = 0;
            int skipped = 0;
            int blocked = 0;
            for (BureauTriggerPort.TriggerRow trigger : due) {
                var result = executionService.executeTrigger(trigger, traceId);
                switch (result.outcome()) {
                    case BureauReportExecutionService.OUTCOME_SENT -> sent++;
                    case BureauReportExecutionService.OUTCOME_BLOCKED -> blocked++;
                    default -> skipped++;
                }
            }
            batchRunPort.finish(JOB_NAME, today, "SUCCESS");
            log.info("报送日批完成 today={} due={} sent={} skipped={} blocked={} traceId={}",
                    today, due.size(), sent, skipped, blocked, traceId);
        } catch (RuntimeException e) {
            batchRunPort.finish(JOB_NAME, today, "FAILED");
            throw e;
        }
    }
}
