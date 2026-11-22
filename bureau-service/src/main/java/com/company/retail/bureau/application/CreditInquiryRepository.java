package com.company.retail.bureau.application;

import com.company.retail.bureau.domain.InquiryStatus;

import java.util.Optional;

/** 征信查询留痕仓储端口（core.credit_inquiries；consent_id NOT NULL，INV-5）。 */
public interface CreditInquiryRepository {

    /** 插入 REQUESTED 留痕，回填 inquiryId。 */
    long insert(long caseId, long consentId, String bureau, String purpose, String traceId);

    /** 推进查询状态（COMPLETED/FAILED），返回是否命中。 */
    boolean markFinished(long inquiryId, InquiryStatus status, String resultSnapshotRef,
                         long costAmount, String traceId);

    Optional<InquiryRow> findById(long inquiryId);

    /** 按案件统计失败次数（3 次重试后转人工，G4/X-2）。 */
    int countFailed(long caseId, String bureau);

    record InquiryRow(long inquiryId, long caseId, long consentId, String bureau,
                      String purpose, String status) {
    }
}
