package com.company.retail.bureau.application;

import java.time.OffsetDateTime;
import java.util.Optional;

/** 征信异议工单仓储端口（integration.bureau_disputes）。 */
public interface BureauDisputeRepository {

    /** 插入 OPEN 工单（due_at = 受理 + SLA），回填 disputeId。 */
    long insert(String customerRef, String bureau, String type, OffsetDateTime dueAt, String traceId);

    Optional<DisputeRow> findById(long disputeId);

    /** 推进工单状态（RESOLVED）。 */
    boolean markResolved(long disputeId, String resolutionRef, String traceId);

    record DisputeRow(long disputeId, String customerRef, String bureau, String type,
                      OffsetDateTime dueAt, String status) {
    }
}
