package com.company.retail.core;

import com.company.retail.core.schedule.BatchRunGuard;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批处理安全护栏：单条 SQL 抢占锁的调用契约（SQL 语义由 DB 冒烟断言覆盖，
 * 此处锁参数与路由行为——占用/跳过/终态 upsert）。
 */
class BatchRunGuardTest {

    private static final LocalDate BIZ_DATE = LocalDate.parse("2026-11-23");

    @Test
    void tryClaim_insertSucceeds_claimed() {
        FakeJdbc jdbc = new FakeJdbc(1);
        BatchRunGuard guard = new BatchRunGuard(jdbc);

        assertTrue(guard.tryClaim("schedule-due-rollup", BIZ_DATE, "SCHED-1", 30));
        assertEquals(1, jdbc.calls.size());
        assertTrue(jdbc.calls.get(0).sql.contains("ON CONFLICT (job_name, biz_date) DO UPDATE"));
        assertTrue(jdbc.calls.get(0).sql.contains("'RUNNING'"));
        assertEquals("schedule-due-rollup", jdbc.calls.get(0).args[0]);
        assertEquals(java.sql.Date.valueOf(BIZ_DATE), jdbc.calls.get(0).args[1]);
        assertEquals("SCHED-1", jdbc.calls.get(0).args[2]);
        assertEquals(30, jdbc.calls.get(0).args[3]);
    }

    @Test
    void tryClaim_conflictNotClaimable_skipped() {
        // SUCCESS 行 / 活跃 RUNNING 行：ON CONFLICT ... WHERE 不命中 → update 返回 0
        FakeJdbc jdbc = new FakeJdbc(0);
        BatchRunGuard guard = new BatchRunGuard(jdbc);

        assertFalse(guard.tryClaim("schedule-due-rollup", BIZ_DATE, "SCHED-1", 30));
    }

    @Test
    void markFinished_upsertsTerminalStatus() {
        FakeJdbc jdbc = new FakeJdbc(1);
        BatchRunGuard guard = new BatchRunGuard(jdbc);

        guard.markFinished("schedule-due-rollup", BIZ_DATE, "DONE");
        assertEquals(1, jdbc.calls.size());
        assertTrue(jdbc.calls.get(0).sql.contains("ON CONFLICT (job_name, biz_date) DO UPDATE"));
        assertEquals("DONE", jdbc.calls.get(0).args[2]);
    }

    /** 捕获 update 调用的桩（护栏只用 update(String, Object...) 一个入口）。 */
    private static final class FakeJdbc extends JdbcTemplate {
        record Call(String sql, Object[] args) {
        }

        final List<Call> calls = new ArrayList<>();
        final int result;

        FakeJdbc(int result) {
            this.result = result;
        }

        @Override
        public int update(String sql, Object... args) {
            calls.add(new Call(sql, args));
            return result;
        }
    }
}
