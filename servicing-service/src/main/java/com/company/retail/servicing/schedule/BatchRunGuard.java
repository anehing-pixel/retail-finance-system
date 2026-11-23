package com.company.retail.servicing.schedule;

import com.company.retail.servicing.mapper.BatchRunMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 批处理安全护栏：基于 {@code core.batch_runs} 的单条 SQL 抢占锁（W9-C，不引入新中间件）。
 *
 * <p>与 retail-core 同构（uk_batch_job_date 唯一约束 + ON CONFLICT DO UPDATE ... WHERE）：
 * 一次原子操作完成「无行则占位 / FAILED 可重试 / 活跃 RUNNING 或同日 SUCCESS 拒绝」。
 * 服务模块各持一份，不建共享配置类（W9-C 约定）。</p>
 */
@Component
public class BatchRunGuard {

    private final BatchRunMapper mapper;

    public BatchRunGuard(BatchRunMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 尝试占用 (jobName, bizDate) 批次。
     *
     * @return true = 抢占成功（调用方执行作业并负责落终态）；false = 已有 SUCCESS 或活跃 RUNNING，跳过
     */
    public boolean tryClaim(String jobName, LocalDate bizDate, String traceId, int staleMinutes) {
        int claimed = mapper.tryClaim(jobName, bizDate, traceId, staleMinutes);
        return claimed > 0;
    }

    /** 落终态（upsert：异常路径下 RUNNING 行可能已随事务回滚，insert 兜底留痕）。 */
    public void markFinished(String jobName, LocalDate bizDate, String status) {
        mapper.markFinished(jobName, bizDate, status);
    }
}
