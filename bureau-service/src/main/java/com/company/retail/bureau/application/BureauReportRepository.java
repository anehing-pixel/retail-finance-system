package com.company.retail.bureau.application;

import java.util.Optional;

/** 征信报送留痕仓储端口（integration.credit_bureau_reports；consent_id NOT NULL，INV-5）。 */
public interface BureauReportRepository {

    /** 插入 PENDING 留痕（uk_report_loan_trigger 唯一；冲突返回 false）。source=AUTO。 */
    boolean insert(long loanId, long consentId, String bureau, String trigger, String traceId);

    /** 插入 PENDING 留痕并显式声明来源（V15 source 列：AUTO=自动通道 / MANUAL=人工通道）。 */
    boolean insertWithSource(long loanId, long consentId, String bureau, String trigger,
                             String source, String traceId);

    /** 推进报送状态（SENT/REJECTED）。 */
    boolean markFinished(long reportId, String status, String payloadRef, String responseRef, String traceId);

    Optional<ReportRow> findById(long reportId);

    /** 按 (loan_id, trigger) 定位（uk_report_loan_trigger 唯一约束的读取侧）。 */
    Optional<ReportRow> findByLoanAndTrigger(long loanId, String trigger);

    record ReportRow(long reportId, long loanId, long consentId, String bureau,
                     String trigger, String status, String source) {
    }
}
