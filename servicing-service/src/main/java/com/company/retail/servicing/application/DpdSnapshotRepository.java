package com.company.retail.servicing.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * DPD 快照仓储端口（表 core.dpd_snapshots；uk_dpd_loan_date 幂等，批处理可重跑）。
 */
public interface DpdSnapshotRepository {

    /** 当日快照（ON CONFLICT (loan_id, biz_date) DO NOTHING）；当日已有返回 false。 */
    boolean insert(long loanId, LocalDate bizDate, int dpdDays, String stage, String traceId);

    /** 当日之前的最近一次快照（分层变化检测用）。 */
    Optional<SnapshotRow> findLatestBefore(long loanId, LocalDate bizDate);

    Optional<SnapshotRow> find(long loanId, LocalDate bizDate);

    List<SnapshotRow> listByLoan(long loanId);

    record SnapshotRow(long snapshotId, long loanId, LocalDate bizDate, int dpdDays,
                       String stage, String dayBasis) {
    }
}
