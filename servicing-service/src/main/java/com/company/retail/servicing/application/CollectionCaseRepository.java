package com.company.retail.servicing.application;

import java.util.List;
import java.util.Optional;

/**
 * 催收案件仓储端口（表 core.collection_cases；实现 infrastructure）。
 */
public interface CollectionCaseRepository {

    /** 新开案件（一债权一在途：status <> CLOSED 部分唯一索引兜底）；已存在在途案件返回 false。 */
    boolean insert(long loanId, String dpdStage, String traceId);

    Optional<CaseRow> findById(long caseId);

    Optional<CaseRow> findOpenByLoan(long loanId);

    /** 状态变更（走聚合方法得出的目标状态；乐观锁 version 兜底并发）。 */
    boolean updateStatus(long caseId, String status, String assignedTo, boolean closed,
                         long expectedVersion, String traceId);

    List<CaseRow> list(String statusOrNull);

    record CaseRow(long caseId, long loanId, String dpdStage, String status,
                   String assignedTo, long version) {
    }
}
