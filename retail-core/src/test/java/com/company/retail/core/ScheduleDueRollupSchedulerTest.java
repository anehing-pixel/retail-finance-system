package com.company.retail.core;

import com.company.retail.core.application.ScheduleDueJobService;
import com.company.retail.core.schedule.BatchRunGuard;
import com.company.retail.core.schedule.RetailJobsScheduleConfig;
import com.company.retail.core.schedule.ScheduleDueRollupScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日批调度器（W9-C）：开关默认关、开关开 cron 触发、并行防重、异常不中断调度线程、终态留痕。
 */
class ScheduleDueRollupSchedulerTest {

    private static final LocalDate BIZ_DATE = LocalDate.parse("2026-11-23");

    // ---------- 开关 ----------

    @Test
    void scheduleDisabledByDefault_noSchedulerBeans() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(PlaceholderConfig.class, RetailJobsScheduleConfig.class,
                    ScheduleDueRollupScheduler.class);
            ctx.refresh();
            // 默认（无 retail.jobs.schedule-enabled）不产生任何调度 bean
            assertFalse(ctx.containsBean("retailJobsScheduleConfig"));
            assertFalse(ctx.containsBean("scheduleDueRollupScheduler"));
        }
    }

    @Test
    void scheduleEnabled_schedulerBeansPresent() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test", Map.of("retail.jobs.schedule-enabled", "true")));
            ctx.register(PlaceholderConfig.class, RetailJobsScheduleConfig.class,
                    ScheduleDueRollupScheduler.class);
            ctx.getBeanFactory().registerSingleton("jobService", new RecordingJobService());
            ctx.getBeanFactory().registerSingleton("guard", new ScriptedGuard(true));
            ctx.refresh();
            assertTrue(ctx.containsBean("retailJobsScheduleConfig"));
            assertTrue(ctx.containsBean("scheduleDueRollupScheduler"));
        }
    }

    // ---------- cron 触发一次执行 ----------

    @Test
    void scheduleEnabled_cronTriggersExecution_marksDone() throws Exception {
        RecordingJobService jobService = new RecordingJobService();
        ScriptedGuard guard = new ScriptedGuard(true);
        try (AnnotationConfigApplicationContext ctx = cronContext(jobService, guard)) {
            long deadline = System.currentTimeMillis() + 5000;
            while (jobService.invocations.isEmpty() && System.currentTimeMillis() < deadline) {
                TimeUnit.MILLISECONDS.sleep(50);
            }
            // 短间隔 cron（每秒）真实触发过至少一轮
            assertTrue(!jobService.invocations.isEmpty(), "cron 未在 5s 内触发");
            assertEquals(BIZ_DATE, jobService.invocations.get(0).bizDate());
            assertEquals("DONE", guard.finishedStatuses.get(0));
        }
    }

    // ---------- 并行防重（第二实例不重复跑） ----------

    @Test
    void parallelSecondInstance_doesNotRunAgain() throws Exception {
        RecordingJobService jobService = new RecordingJobService();
        // 第一个实例抢占成功、第二个实例抢占失败（同一批次行）
        ScriptedGuard guard = new ScriptedGuard(true, false);
        jobService.holdExecution = new CountDownLatch(1);

        ScheduleDueRollupScheduler first = new ScheduleDueRollupScheduler(jobService, guard, 30);
        ScheduleDueRollupScheduler second = new ScheduleDueRollupScheduler(jobService, guard, 30);

        Thread t1 = new Thread(first::runScheduled, "instance-1");
        Thread t2 = new Thread(second::runScheduled, "instance-2");
        t1.start();
        // 等 t1 真正进入执行体（rollUp 阻塞中）再放第二实例
        while (jobService.entered.getCount() == 1) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        t2.start();
        t2.join(5000);
        // 第二实例此刻已因抢占失败返回，第一实例继续跑完
        assertEquals(1, jobService.rollUpCalls.get());
        jobService.holdExecution.countDown();
        t1.join(5000);
        assertEquals(1, jobService.rollUpCalls.get());
        assertEquals(1, guard.finishedStatuses.size());
        assertEquals("DONE", guard.finishedStatuses.get(0));
    }

    // ---------- 异常不中断调度线程 + FAILED 留痕 ----------

    @Test
    void executionFailure_marksFailed_doesNotThrow() {
        RecordingJobService jobService = new RecordingJobService();
        jobService.failOnRollUp = true;
        ScriptedGuard guard = new ScriptedGuard(true);

        new ScheduleDueRollupScheduler(jobService, guard, 30).runScheduled();

        assertEquals(1, jobService.rollUpCalls.get());
        assertEquals(List.of("FAILED"), guard.finishedStatuses);
    }

    @Test
    void claimSkipped_jobNotInvoked() {
        RecordingJobService jobService = new RecordingJobService();
        ScriptedGuard guard = new ScriptedGuard(false);

        new ScheduleDueRollupScheduler(jobService, guard, 30).runScheduled();

        assertEquals(0, jobService.rollUpCalls.get());
        assertEquals(0, guard.finishedStatuses.size());
    }

    // ---------- 测试基建 ----------

    private static AnnotationConfigApplicationContext cronContext(
            ScheduleDueJobService jobService, BatchRunGuard guard) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("retail.jobs.schedule-enabled", "true",
                        "retail.jobs.schedule-cron", "*/1 * * * * *")));
        ctx.register(PlaceholderConfig.class, CronJobConfig.class);
        ctx.getBeanFactory().registerSingleton("jobService", jobService);
        ctx.getBeanFactory().registerSingleton("guard", guard);
        ctx.refresh();
        return ctx;
    }

    @Configuration
    static class PlaceholderConfig {
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }

    /** 开关打开 + @EnableScheduling 生效的最小调度上下文。 */
    @Configuration
    @EnableScheduling
    static class CronJobConfig {
        @Bean
        public ScheduleDueRollupScheduler scheduler(ScheduleDueJobService jobService,
                                                    BatchRunGuard guard) {
            return new ScheduleDueRollupScheduler(jobService, guard, 30);
        }
    }

    /** 记录触发的假作业服务（不触 DB）。 */
    static final class RecordingJobService extends ScheduleDueJobService {
        record Invocation(LocalDate bizDate, String traceId) {
        }

        final List<Invocation> invocations = new CopyOnWriteArrayList<>();
        final AtomicInteger rollUpCalls = new AtomicInteger();
        volatile boolean failOnRollUp;
        volatile CountDownLatch holdExecution;
        final CountDownLatch entered = new CountDownLatch(1);

        RecordingJobService() {
            super(null, null);
        }

        @Override
        public Map<String, Object> rollUp(LocalDate bizDate, String traceId) {
            rollUpCalls.incrementAndGet();
            invocations.add(new Invocation(bizDate, traceId));
            entered.countDown();
            if (holdExecution != null) {
                try {
                    holdExecution.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failOnRollUp) {
                throw new IllegalStateException("模拟批处理失败");
            }
            return Map.of("jobName", "schedule-due-rollup");
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
            boolean claim = cursor < outcomes.length && outcomes[cursor++];
            return claim;
        }

        @Override
        public synchronized void markFinished(String jobName, LocalDate bizDate, String status) {
            finishedStatuses.add(status);
        }
    }
}
