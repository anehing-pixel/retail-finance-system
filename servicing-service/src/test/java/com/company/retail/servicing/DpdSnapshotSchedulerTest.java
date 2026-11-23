package com.company.retail.servicing;

import com.company.retail.servicing.application.DpdBatchAppService;
import com.company.retail.servicing.schedule.BatchRunGuard;
import com.company.retail.servicing.schedule.DpdSnapshotScheduler;
import com.company.retail.servicing.schedule.ServicingJobsScheduleConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DPD 日批调度器（W9-C）：开关默认关、抢占成功才执行、并行防重、异常不中断调度线程。
 */
class DpdSnapshotSchedulerTest {

    private static final LocalDate BIZ_DATE = LocalDate.parse("2026-11-23");

    @Test
    void scheduleDisabledByDefault_noSchedulerBeans() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(PlaceholderConfig.class, ServicingJobsScheduleConfig.class,
                    DpdSnapshotScheduler.class);
            ctx.refresh();
            assertFalse(ctx.containsBean("servicingJobsScheduleConfig"));
            assertFalse(ctx.containsBean("dpdSnapshotScheduler"));
        }
    }

    @Test
    void scheduleEnabled_schedulerBeansPresent() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test", Map.of("retail.jobs.schedule-enabled", "true")));
            ctx.register(PlaceholderConfig.class, ServicingJobsScheduleConfig.class,
                    DpdSnapshotScheduler.class);
            ctx.getBeanFactory().registerSingleton("dpdBatchAppService", new RecordingDpdService());
            ctx.getBeanFactory().registerSingleton("batchRunGuard", new ScriptedGuard(true));
            ctx.refresh();
            assertTrue(ctx.containsBean("servicingJobsScheduleConfig"));
            assertTrue(ctx.containsBean("dpdSnapshotScheduler"));
        }
    }

    @Test
    void claimSuccess_runsAsSystem() {
        RecordingDpdService dpdService = new RecordingDpdService();
        ScriptedGuard guard = new ScriptedGuard(true);

        new DpdSnapshotScheduler(dpdService, guard, 30).runScheduled();

        assertEquals(1, dpdService.calls.get());
        assertEquals(BIZ_DATE, dpdService.lastBizDate);
        // 调度上下文无请求 → actor=SYSTEM（B-9）
        assertEquals("SYSTEM", dpdService.lastActor);
        assertEquals(0, guard.finishedStatuses.size()); // 终态由 run() 自己留痕
    }

    @Test
    void claimSkipped_jobNotInvoked() {
        RecordingDpdService dpdService = new RecordingDpdService();
        ScriptedGuard guard = new ScriptedGuard(false);

        new DpdSnapshotScheduler(dpdService, guard, 30).runScheduled();

        assertEquals(0, dpdService.calls.get());
    }

    @Test
    void executionFailure_marksFailed_doesNotThrow() {
        RecordingDpdService dpdService = new RecordingDpdService();
        dpdService.fail = true;
        ScriptedGuard guard = new ScriptedGuard(true);

        assertDoesNotThrow(() -> new DpdSnapshotScheduler(dpdService, guard, 30).runScheduled());

        assertEquals(1, dpdService.calls.get());
        assertEquals(List.of("FAILED"), guard.finishedStatuses);
    }

    // ---------- 测试基建 ----------

    @Configuration
    static class PlaceholderConfig {
        @org.springframework.context.annotation.Bean
        static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }

    /** 记录触发的假批处理用例（不触 DB）。 */
    static final class RecordingDpdService extends DpdBatchAppService {
        final AtomicInteger calls = new AtomicInteger();
        volatile LocalDate lastBizDate;
        volatile String lastActor;
        volatile boolean fail;

        RecordingDpdService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public BatchSummary run(LocalDate bizDate, String actor, String traceId) {
            calls.incrementAndGet();
            lastBizDate = bizDate;
            lastActor = actor;
            if (fail) {
                throw new IllegalStateException("模拟 DPD 批处理失败");
            }
            return new BatchSummary(bizDate, 0, 0, 0, 0);
        }
    }

    /** 按脚本返回抢占结果的假护栏。 */
    static final class ScriptedGuard extends BatchRunGuard {
        private final boolean[] outcomes;
        private int cursor;
        final List<String> finishedStatuses = new CopyOnWriteArrayList<>();

        ScriptedGuard(boolean... outcomes) {
            super(null);
            this.outcomes = outcomes;
        }

        @Override
        public synchronized boolean tryClaim(String jobName, LocalDate bizDate,
                                             String traceId, int staleMinutes) {
            return cursor < outcomes.length && outcomes[cursor++];
        }

        @Override
        public synchronized void markFinished(String jobName, LocalDate bizDate, String status) {
            finishedStatuses.add(status);
        }
    }
}
