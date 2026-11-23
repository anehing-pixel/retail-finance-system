package com.company.retail.recon.infrastructure;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.recon.application.ReconBatchRunRepository;
import com.company.retail.recon.mapper.ReconBatchRunMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 批处理留痕 MyBatis 实现（core.batch_runs；uk_batch_job_date 同日重跑复用同一行，A28）。
 */
public class MyBatisReconBatchRunRepository implements ReconBatchRunRepository {

    private final ReconBatchRunMapper mapper;

    public MyBatisReconBatchRunRepository(ReconBatchRunMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void start(String jobName, String bizDate, String traceId) {
        mapper.start(jobName, LocalDate.parse(bizDate), traceId);
    }

    @Override
    public void finish(String jobName, String bizDate, String status) {
        mapper.finish(jobName, LocalDate.parse(bizDate), status);
    }

    @Override
    public List<RunRow> list(String jobNameOrNull) {
        return mapper.list(jobNameOrNull).stream()
                .map(MyBatisReconBatchRunRepository::toRow)
                .toList();
    }

    private static RunRow toRow(Map<String, Object> r) {
        return new RunRow(
                RowValues.asLong(r, "run_id"),
                RowValues.asString(r, "job_name"),
                String.valueOf(r.get("biz_date")),
                RowValues.asString(r, "status"));
    }
}
