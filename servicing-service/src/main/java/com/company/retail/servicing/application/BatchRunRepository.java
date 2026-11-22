package com.company.retail.servicing.application;

import java.time.LocalDate;

/**
 * 批处理留痕仓储端口（表 core.batch_runs；uk_batch_job_date 幂等，重跑不产生第二份）。
 */
public interface BatchRunRepository {

    /** 开始一轮（同 job+bizDate 重跑 → 复位 RUNNING，不留第二行，A28）。 */
    void start(String jobName, LocalDate bizDate, String traceId);

    /** 结束（SUCCESS / FAILED + finished_at）。 */
    void finish(String jobName, LocalDate bizDate, String status);
}
