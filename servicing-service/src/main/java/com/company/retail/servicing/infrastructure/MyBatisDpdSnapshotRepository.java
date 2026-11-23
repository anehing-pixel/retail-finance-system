package com.company.retail.servicing.infrastructure;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.servicing.application.DpdSnapshotRepository;
import com.company.retail.servicing.mapper.DpdSnapshotMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DPD 快照 MyBatis 仓储（core.dpd_snapshots；uk_dpd_loan_date 幂等）。
 */
public class MyBatisDpdSnapshotRepository implements DpdSnapshotRepository {

    private final DpdSnapshotMapper mapper;

    public MyBatisDpdSnapshotRepository(DpdSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean insert(long loanId, LocalDate bizDate, int dpdDays, String stage, String traceId) {
        return mapper.insert(loanId, bizDate, dpdDays, stage, traceId) == 1;
    }

    @Override
    public Optional<SnapshotRow> findLatestBefore(long loanId, LocalDate bizDate) {
        List<Map<String, Object>> rows = mapper.findLatestBefore(loanId, bizDate);
        return rows.stream().findFirst().map(MyBatisDpdSnapshotRepository::toRow);
    }

    @Override
    public Optional<SnapshotRow> find(long loanId, LocalDate bizDate) {
        List<Map<String, Object>> rows = mapper.find(loanId, bizDate);
        return rows.stream().findFirst().map(MyBatisDpdSnapshotRepository::toRow);
    }

    @Override
    public List<SnapshotRow> listByLoan(long loanId) {
        return mapper.listByLoan(loanId).stream()
                .map(MyBatisDpdSnapshotRepository::toRow)
                .toList();
    }

    private static SnapshotRow toRow(Map<String, Object> r) {
        return new SnapshotRow(
                RowValues.asLong(r, "snapshot_id"),
                RowValues.asLong(r, "loan_id"),
                RowValues.asLocalDate(r, "biz_date"),
                RowValues.asInt(r, "dpd_days"),
                RowValues.asString(r, "stage"),
                RowValues.asString(r, "day_basis"));
    }
}
