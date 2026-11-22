package com.company.retail.bureau.infrastructure;

import com.company.retail.bureau.application.BureauTriggerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 报送触发登记 JDBC 读取/状态推进仓储（core.bureau_report_triggers，V14）。
 *
 * <p>跨域只读投影：servicing 是本表真相源（触发登记），bureau 作为执行方
 * 只读 + 推进状态（R02 §6 真相源边界；跨域读已登记最终报告共享区建议）。</p>
 */
public class JdbcBureauTriggerRepository implements BureauTriggerPort {

    private static final String SELECT =
            "SELECT trigger_id, loan_id, dpd_stage, trigger_type, consent_id, target_biz_date, status "
                    + "FROM core.bureau_report_triggers ";

    private final JdbcTemplate jdbc;

    public JdbcBureauTriggerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TriggerRow> findByLoanAndType(long loanId, String triggerType) {
        List<TriggerRow> rows = jdbc.query(SELECT + "WHERE loan_id = ? AND trigger_type = ?",
                (rs, i) -> mapRow(rs.getLong("trigger_id"), rs.getLong("loan_id"),
                        rs.getString("dpd_stage"), rs.getString("trigger_type"),
                        rs.getLong("consent_id"), rs.getDate("target_biz_date"), rs.getString("status")),
                loanId, triggerType);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<TriggerRow> findDue(LocalDate today) {
        return jdbc.query(SELECT + "WHERE status = 'REGISTERED' AND target_biz_date <= ? "
                        + "ORDER BY trigger_id",
                (rs, i) -> mapRow(rs.getLong("trigger_id"), rs.getLong("loan_id"),
                        rs.getString("dpd_stage"), rs.getString("trigger_type"),
                        rs.getLong("consent_id"), rs.getDate("target_biz_date"), rs.getString("status")),
                Date.valueOf(today));
    }

    @Override
    public boolean updateStatus(long triggerId, String status) {
        return jdbc.update("UPDATE core.bureau_report_triggers SET status = ?, "
                        + "version = version + 1, updated_at = now() WHERE trigger_id = ?",
                status, triggerId) > 0;
    }

    private static TriggerRow mapRow(long triggerId, long loanId, String dpdStage, String triggerType,
                                     long consentId, Date targetBizDate, String status) {
        return new TriggerRow(triggerId, loanId, dpdStage, triggerType, consentId,
                targetBizDate == null ? null : targetBizDate.toLocalDate(), status);
    }
}
