package com.company.retail.bureau.infrastructure;

import com.company.retail.bureau.application.BureauBatchRunPort;
import com.company.retail.bureau.mapper.BureauBatchRunMapper;

import java.time.LocalDate;

/**
 * 批处理留痕 MyBatis 仓储（core.batch_runs；uk_batch_job_date 同日重跑复用同一行）。
 */
public class MyBatisBureauBatchRunRepository implements BureauBatchRunPort {

    private final BureauBatchRunMapper mapper;

    public MyBatisBureauBatchRunRepository(BureauBatchRunMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void start(String jobName, LocalDate bizDate, String traceId) {
        mapper.start(jobName, bizDate, traceId);
    }

    @Override
    public void finish(String jobName, LocalDate bizDate, String status) {
        mapper.finish(jobName, bizDate, status);
    }
}
