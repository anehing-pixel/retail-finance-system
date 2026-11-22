package com.company.retail.core.persistence;

import com.company.retail.core.domain.ScheduleItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;

/**
 * {@code core.repayment_plans} / {@code core.schedule_items} 持久化（V6 表）。
 * uk_plan_loan_version：一个债权一个计划版本（本波次只落 version 1，改版归后续波次）。
 */
@Repository
public class JdbcRepaymentPlanRepository {

    private static final RowMapper<ScheduleItem> ITEM_MAPPER = (rs, i) -> new ScheduleItem(
            rs.getInt("period_no"),
            rs.getDate("due_date").toLocalDate(),
            rs.getLong("amount_due"),
            rs.getLong("principal_due"),
            rs.getLong("interest_due"),
            rs.getString("status"),
            rs.getLong("paid_amount"));

    private final JdbcTemplate jdbc;

    public JdbcRepaymentPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertPlan(long loanId, String method, int installmentCount, java.time.LocalDate firstDueDate,
                           Integer bonusMonth, String traceId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO core.repayment_plans (loan_id, method, installment_count, first_due_date, "
                            + "bonus_month, plan_version, trace_id) VALUES (?, ?, ?, ?, ?, 1, ?)",
                    new String[]{"plan_id"});
            ps.setLong(1, loanId);
            ps.setString(2, method);
            ps.setInt(3, installmentCount);
            ps.setDate(4, Date.valueOf(firstDueDate));
            if (bonusMonth == null) {
                ps.setObject(5, null);
            } else {
                ps.setInt(5, bonusMonth);
            }
            ps.setString(6, traceId);
            return ps;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    public void insertItems(long planId, List<ScheduleItem> items, String traceId) {
        jdbc.batchUpdate(
                "INSERT INTO core.schedule_items (plan_id, period_no, due_date, amount_due, principal_due, "
                        + "interest_due, status, paid_amount, trace_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                items, items.size(),
                (ps, item) -> {
                    ps.setLong(1, planId);
                    ps.setInt(2, item.periodNo());
                    ps.setDate(3, Date.valueOf(item.dueDate()));
                    ps.setLong(4, item.amountDue());
                    ps.setLong(5, item.principalDue());
                    ps.setLong(6, item.interestDue());
                    ps.setString(7, item.status());
                    ps.setLong(8, item.paidAmount());
                    ps.setString(9, traceId);
                });
    }

    public List<ScheduleItem> findItems(long planId) {
        return jdbc.query(
                "SELECT * FROM core.schedule_items WHERE plan_id = ? ORDER BY period_no", ITEM_MAPPER, planId);
    }

    public boolean planExistsForLoan(long loanId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM core.repayment_plans WHERE loan_id = ?", Integer.class, loanId);
        return count != null && count > 0;
    }
}
