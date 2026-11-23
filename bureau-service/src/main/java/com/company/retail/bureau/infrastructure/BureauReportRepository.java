package com.company.retail.bureau.infrastructure;

import com.company.retail.bureau.mapper.BureauReportMapper;
import com.company.retail.common.persistence.RowValues;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 征信报送留痕 MyBatis 仓储（integration.credit_bureau_reports；
 * uk_report_loan_trigger (loan_id, trigger) 幂等兜底；consent_id NOT NULL，INV-5）。
 *
 * <p>本实现与端口 {@code com.company.retail.bureau.application.BureauReportRepository} 同名，
 * 故 implements 子句用全限定名；端口内的 {@code ReportRow} 记录由本类继承使用，
 * 对外方法签名与语义一字未改。</p>
 */
public class BureauReportRepository
        implements com.company.retail.bureau.application.BureauReportRepository {

    private final BureauReportMapper mapper;

    public BureauReportRepository(BureauReportMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean insert(long loanId, long consentId, String bureau, String trigger, String traceId) {
        return insertWithSource(loanId, consentId, bureau, trigger, "AUTO", traceId);
    }

    @Override
    public boolean insertWithSource(long loanId, long consentId, String bureau, String trigger,
                                    String source, String traceId) {
        // ON CONFLICT DO NOTHING：唯一冲突（uk_report_loan_trigger）不再抛异常、受影响行数为 0，
        // 与原实现捕获 DuplicateKeyException 后返回 false 的语义一致（RF_CONFLICT_001）。
        return mapper.insertPending(loanId, consentId, bureau, trigger, source, traceId) > 0;
    }

    @Override
    public boolean markFinished(long reportId, String status, String payloadRef, String responseRef,
                                String traceId) {
        return mapper.markFinished(reportId, status, payloadRef, responseRef, traceId) > 0;
    }

    @Override
    public Optional<ReportRow> findById(long reportId) {
        List<Map<String, Object>> rows = mapper.findById(reportId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toRow(rows.get(0)));
    }

    @Override
    public Optional<ReportRow> findByLoanAndTrigger(long loanId, String trigger) {
        List<Map<String, Object>> rows = mapper.findByLoanAndTrigger(loanId, trigger);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toRow(rows.get(0)));
    }

    private static ReportRow toRow(Map<String, Object> row) {
        return new ReportRow(RowValues.asLong(row, "report_id"),
                RowValues.asLong(row, "loan_id"),
                RowValues.asLong(row, "consent_id"),
                RowValues.asString(row, "bureau"),
                RowValues.asString(row, "trigger"),
                RowValues.asString(row, "status"),
                RowValues.asString(row, "source"));
    }
}
