package com.company.retail.core;

import com.company.retail.core.mapper.BatchRunMapper;
import com.company.retail.core.schedule.BatchRunGuard;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批处理安全护栏：单条 SQL 抢占锁的调用契约（SQL 语义由 DB 冒烟断言覆盖，
 * 此处锁参数与路由行为——占用/跳过/终态 upsert）。
 *
 * <p>MyBatis 化后：SQL 文本在 Mapper 的 {@code @Insert} 注解上（此处断言其关键子串），
 * 调用契约由假 Mapper 承接。</p>
 */
class BatchRunGuardTest {

    private static final LocalDate BIZ_DATE = LocalDate.parse("2026-11-23");

    @Test
    void tryClaim_insertSucceeds_claimed() {
        FakeBatchRunMapper mapper = new FakeBatchRunMapper(1);
        BatchRunGuard guard = new BatchRunGuard(mapper);

        assertTrue(guard.tryClaim("schedule-due-rollup", BIZ_DATE, "SCHED-1", 30));
        assertEquals(1, mapper.calls.size());
        assertEquals("schedule-due-rollup", mapper.calls.get(0).jobName());
        assertEquals(BIZ_DATE, mapper.calls.get(0).bizDate());
        assertEquals("SCHED-1", mapper.calls.get(0).traceId());
        assertEquals(30, mapper.calls.get(0).staleMinutes());

        // 抢占 SQL 语义钉死：唯一约束冲突更新 + RUNNING 占位 + 僵尸接管条件
        String sql = sqlOf("tryClaim", String.class, LocalDate.class, String.class, int.class);
        assertTrue(sql.contains("ON CONFLICT (job_name, biz_date) DO UPDATE"));
        assertTrue(sql.contains("'RUNNING'"));
        assertTrue(sql.contains("make_interval"));
    }

    @Test
    void tryClaim_conflictNotClaimable_skipped() {
        // SUCCESS 行 / 活跃 RUNNING 行：ON CONFLICT ... WHERE 不命中 → update 返回 0
        FakeBatchRunMapper mapper = new FakeBatchRunMapper(0);
        BatchRunGuard guard = new BatchRunGuard(mapper);

        assertFalse(guard.tryClaim("schedule-due-rollup", BIZ_DATE, "SCHED-1", 30));
    }

    @Test
    void markFinished_upsertsTerminalStatus() {
        FakeBatchRunMapper mapper = new FakeBatchRunMapper(1);
        BatchRunGuard guard = new BatchRunGuard(mapper);

        guard.markFinished("schedule-due-rollup", BIZ_DATE, "DONE");
        assertEquals(1, mapper.finished.size());
        assertEquals("DONE", mapper.finished.get(0).status());

        String sql = sqlOf("markFinished", String.class, LocalDate.class, String.class);
        assertTrue(sql.contains("ON CONFLICT (job_name, biz_date) DO UPDATE"));
    }

    /** 读取 Mapper 方法上的 SQL 注解文本（SQL 语义钉在注解上）。 */
    private static String sqlOf(String methodName, Class<?>... paramTypes) {
        try {
            Method m = BatchRunMapper.class.getMethod(methodName, paramTypes);
            Insert insert = m.getAnnotation(Insert.class);
            assertNotNull(insert, "Mapper 方法必须带 @Insert 注解");
            return String.join(" ", insert.value());
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    /** 捕获抢占/终态调用的桩。 */
    private static final class FakeBatchRunMapper implements BatchRunMapper {

        record Claim(String jobName, LocalDate bizDate, String traceId, int staleMinutes) {
        }

        record Finished(String jobName, LocalDate bizDate, String status) {
        }

        final List<Claim> calls = new ArrayList<>();
        final List<Finished> finished = new ArrayList<>();
        final int result;

        FakeBatchRunMapper(int result) {
            this.result = result;
        }

        @Override
        public int tryClaim(String jobName, LocalDate bizDate, String traceId, int staleMinutes) {
            calls.add(new Claim(jobName, bizDate, traceId, staleMinutes));
            return result;
        }

        @Override
        public int markFinished(String jobName, LocalDate bizDate, String status) {
            finished.add(new Finished(jobName, bizDate, status));
            return result;
        }

        @Override
        public int recordDone(String jobName, LocalDate bizDate, String traceId) {
            finished.add(new Finished(jobName, bizDate, "DONE"));
            return result;
        }
    }
}
