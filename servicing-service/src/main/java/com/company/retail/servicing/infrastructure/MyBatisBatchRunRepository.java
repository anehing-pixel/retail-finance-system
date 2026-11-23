package com.company.retail.servicing.infrastructure;

import com.company.retail.servicing.application.BatchRunRepository;
import com.company.retail.servicing.mapper.BatchRunMapper;

import java.time.LocalDate;

/**
 * 批处理留痕 MyBatis 仓储（core.batch_runs；uk_batch_job_date 同日重跑复用同一行，A28）。
 */
public class MyBatisBatchRunRepository implements BatchRunRepository {

    private final BatchRunMapper mapper;

    public MyBatisBatchRunRepository(BatchRunMapper mapper) {
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
