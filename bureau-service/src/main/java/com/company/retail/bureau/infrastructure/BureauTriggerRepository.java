package com.company.retail.bureau.infrastructure;

import com.company.retail.bureau.application.BureauTriggerPort;
import com.company.retail.bureau.mapper.BureauTriggerMapper;
import com.company.retail.common.persistence.RowValues;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 报送触发登记 MyBatis 读取/状态推进仓储（core.bureau_report_triggers，V14）。
 *
 * <p>跨域只读投影：servicing 是本表真相源（触发登记），bureau 作为执行方
 * 只读 + 推进状态（R02 §6 真相源边界；跨域读已登记最终报告共享区建议）。</p>
 */
public class BureauTriggerRepository implements BureauTriggerPort {

    private final BureauTriggerMapper mapper;

    public BureauTriggerRepository(BureauTriggerMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<TriggerRow> findByLoanAndType(long loanId, String triggerType) {
        List<Map<String, Object>> rows = mapper.findByLoanAndType(loanId, triggerType);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toRow(rows.get(0)));
    }

    @Override
    public List<TriggerRow> findDue(LocalDate today) {
        return mapper.findDue(today).stream().map(BureauTriggerRepository::toRow).toList();
    }

    @Override
    public boolean updateStatus(long triggerId, String status) {
        return mapper.updateStatus(triggerId, status) > 0;
    }

    private static TriggerRow toRow(Map<String, Object> row) {
        return new TriggerRow(RowValues.asLong(row, "trigger_id"),
                RowValues.asLong(row, "loan_id"),
                RowValues.asString(row, "dpd_stage"),
                RowValues.asString(row, "trigger_type"),
                RowValues.asLong(row, "consent_id"),
                RowValues.asLocalDate(row, "target_biz_date"),
                RowValues.asString(row, "status"));
    }
}
