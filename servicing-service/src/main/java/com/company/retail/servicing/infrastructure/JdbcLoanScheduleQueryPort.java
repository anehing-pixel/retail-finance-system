package com.company.retail.servicing.infrastructure;

import com.company.retail.servicing.application.LoanScheduleQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 债权 / 还款计划只读查询 JDBC 实现（core.loans / core.repayment_plans / core.schedule_items）。
 *
 * <p>只读：不 UPDATE/DELETE 他域表（R02 §6 真相源边界）；W6 未合入时按表字段直接读，
 * 不 import retail-core 代码。</p>
 */
public class JdbcLoanScheduleQueryPort implements LoanScheduleQueryPort {

    /** 债权终态（B-7 四分 + CANCELLED）：终态不再做 DPD 快照。 */
    private static final String TERMINAL_LOAN_STATUSES =
            "('SETTLED', 'EARLY_SETTLED', 'WRITTEN_OFF', 'CANCELLED')";

    private final JdbcTemplate jdbc;

    public JdbcLoanScheduleQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<LoanView> loadLoansForDpd() {
        return jdbc.queryForList("SELECT loan_id, status FROM core.loans WHERE status NOT IN "
                        + TERMINAL_LOAN_STATUSES + " ORDER BY loan_id")
                .stream()
                .map(r -> new LoanView(((Number) r.get("loan_id")).longValue(),
                        (String) r.get("status")))
                .toList();
    }

    @Override
    public List<ItemView> loadScheduleItems() {
        return jdbc.queryForList(
                        "SELECT si.item_id, p.loan_id, si.due_date, si.status "
                                + "FROM core.schedule_items si "
                                + "JOIN core.repayment_plans p ON p.plan_id = si.plan_id "
                                + "ORDER BY si.item_id")
                .stream()
                .map(r -> new ItemView(((Number) r.get("item_id")).longValue(),
                        ((Number) r.get("loan_id")).longValue(),
                        ((java.sql.Date) r.get("due_date")).toLocalDate(),
                        (String) r.get("status")))
                .toList();
    }
}
