package com.company.retail.recon.application;

import java.util.List;
import java.util.Optional;

/**
 * 批处理留痕仓储端口（复用 core.batch_runs，V1；uk_batch_job_date 幂等）。
 */
public interface ReconBatchRunRepository {

    void start(String jobName, String bizDate, String traceId);

    void finish(String jobName, String bizDate, String status);

    List<RunRow> list(String jobNameOrNull);

    record RunRow(long runId, String jobName, String bizDate, String status) {
    }
}
