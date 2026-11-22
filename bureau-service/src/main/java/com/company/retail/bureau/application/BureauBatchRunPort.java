package com.company.retail.bureau.application;

import java.time.LocalDate;

/**
 * 批处理留痕端口（表 core.batch_runs；uk_batch_job_date 同日重跑复用同一行，A28）。
 *
 * <p>机制与 servicing 的 BatchRunRepository 同构；bureau 自持一份（语义层隔离，
 * ADR-RF-003），不跨模块引用 servicing 代码。</p>
 */
public interface BureauBatchRunPort {

    void start(String jobName, LocalDate bizDate, String traceId);

    void finish(String jobName, LocalDate bizDate, String status);
}
