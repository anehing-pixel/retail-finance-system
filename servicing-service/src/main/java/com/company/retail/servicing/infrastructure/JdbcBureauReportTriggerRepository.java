package com.company.retail.servicing.infrastructure;

import com.company.retail.servicing.application.BureauReportTriggerRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 征信报送触发登记 JDBC 仓储（core.bureau_report_triggers，V14；uk_brt_loan_type 幂等）。
 */
public class JdbcBureauReportTriggerRepository implements BureauReportTriggerRepository {

    private final JdbcTemplate jdbc;

    public JdbcBureauReportTriggerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(long loanId, String dpdStage, String triggerType, long consentId,
                          LocalDate targetBizDate, String traceId) {
        try {
            jdbc.update("INSERT INTO core.bureau_report_triggers "
                            + "(loan_id, dpd_stage, trigger_type, consent_id, target_biz_date, trace_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    loanId, dpdStage, triggerType, consentId, targetBizDate, traceId);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;   // uk_brt_loan_type
        }
    }

    @Override
    public Optional<TriggerRow> findById(long triggerId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT trigger_id, loan_id, dpd_stage, trigger_type, consent_id, target_biz_date, status "
                        + "FROM core.bureau_report_triggers WHERE trigger_id = ?", triggerId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toRow(rows.get(0)));
    }

    @Override
    public Optional<TriggerRow> findByLoanAndType(long loanId, String triggerType) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT trigger_id, loan_id, dpd_stage, trigger_type, consent_id, target_biz_date, status "
                        + "FROM core.bureau_report_triggers WHERE loan_id = ? AND trigger_type = ?",
                loanId, triggerType);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toRow(rows.get(0)));
    }

    @Override
    public List<TriggerRow> list(String statusOrNull) {
        StringBuilder sql = new StringBuilder(
                "SELECT trigger_id, loan_id, dpd_stage, trigger_type, consent_id, target_biz_date, status "
                        + "FROM core.bureau_report_triggers");
        List<Object> args = new ArrayList<>();
        if (statusOrNull != null && !statusOrNull.isBlank()) {
            sql.append(" WHERE status = ?");
            args.add(statusOrNull);
        }
        sql.append(" ORDER BY trigger_id DESC LIMIT 200");
        return jdbc.queryForList(sql.toString(), args.toArray()).stream().map(this::toRow).toList();
    }

    @Override
    public boolean updateStatus(long triggerId, String status, String traceId) {
        return jdbc.update("UPDATE core.bureau_report_triggers SET status = ?, trace_id = ?, "
                        + "updated_at = now(), version = version + 1 WHERE trigger_id = ?",
                status, traceId, triggerId) == 1;
    }

    private TriggerRow toRow(Map<String, Object> r) {
        return new TriggerRow(
                ((Number) r.get("trigger_id")).longValue(),
                ((Number) r.get("loan_id")).longValue(),
                (String) r.get("dpd_stage"),
                (String) r.get("trigger_type"),
                ((Number) r.get("consent_id")).longValue(),
                ((java.sql.Date) r.get("target_biz_date")).toLocalDate(),
                (String) r.get("status"));
    }
}
