package com.company.retail.servicing.infrastructure;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.servicing.application.BureauReportTriggerRepository;
import com.company.retail.servicing.mapper.BureauReportTriggerMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 征信报送触发登记 MyBatis 仓储（core.bureau_report_triggers，V14；uk_brt_loan_type 幂等）。
 */
public class MyBatisBureauReportTriggerRepository implements BureauReportTriggerRepository {

    private final BureauReportTriggerMapper mapper;

    public MyBatisBureauReportTriggerRepository(BureauReportTriggerMapper mapper) {
        this.mapper = mapper;
    }

    /** uk_brt_loan_type 冲突（并发被抢先）返回 false，不抛异常。 */
    @Override
    public boolean insert(long loanId, String dpdStage, String triggerType, long consentId,
                          LocalDate targetBizDate, String traceId) {
        return mapper.insert(loanId, dpdStage, triggerType, consentId, targetBizDate, traceId) == 1;
    }

    @Override
    public Optional<TriggerRow> findById(long triggerId) {
        List<Map<String, Object>> rows = mapper.findById(triggerId);
        return rows.stream().findFirst().map(MyBatisBureauReportTriggerRepository::toRow);
    }

    @Override
    public Optional<TriggerRow> findByLoanAndType(long loanId, String triggerType) {
        List<Map<String, Object>> rows = mapper.findByLoanAndType(loanId, triggerType);
        return rows.stream().findFirst().map(MyBatisBureauReportTriggerRepository::toRow);
    }

    @Override
    public List<TriggerRow> list(String statusOrNull) {
        return mapper.list(statusOrNull).stream()
                .map(MyBatisBureauReportTriggerRepository::toRow)
                .toList();
    }

    @Override
    public boolean updateStatus(long triggerId, String status, String traceId) {
        return mapper.updateStatus(triggerId, status, traceId) == 1;
    }

    private static TriggerRow toRow(Map<String, Object> r) {
        return new TriggerRow(
                RowValues.asLong(r, "trigger_id"),
                RowValues.asLong(r, "loan_id"),
                RowValues.asString(r, "dpd_stage"),
                RowValues.asString(r, "trigger_type"),
                RowValues.asLong(r, "consent_id"),
                RowValues.asLocalDate(r, "target_biz_date"),
                RowValues.asString(r, "status"));
    }
}
