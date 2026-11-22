package com.company.retail.core.persistence;

import com.company.retail.core.domain.ScheduleItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * {@code core.repayment_plans} / {@code core.schedule_items} 持久化（V6 表 + V13 核销拆分列）。
 * uk_plan_loan_version：一个债权一个计划版本（本波次只落 version 1，改版归后续波次）。
 *
 * <p><b>schedule_items 状态唯一写者（W6）</b>：期次状态迁移只经本类的
 * {@code updateItemAfterAllocation} / {@code updateItemAfterClosure} / {@code rollDue} /
 * {@code rollOverdue} 四个口径，禁止散落 SQL 直接 SET status（R00 §5 / R04 §3.6）。</p>
 */
@Repository
public class JdbcRepaymentPlanRepository {

    /** 期次核销快照（W6 核销/结清/对拍的行级视图，含 V13 拆分列）。 */
    public record ItemRow(long itemId, long planId, int periodNo, java.time.LocalDate dueDate,
                          long amountDue, long principalDue, long interestDue,
                          String status, long paidAmount, long paidInterest,
                          long paidPrincipal, long waivedAmount, int version) {
    }

    private static final RowMapper<ScheduleItem> ITEM_MAPPER = (rs, i) -> new ScheduleItem(
            rs.getInt("period_no"),
            rs.getDate("due_date").toLocalDate(),
            rs.getLong("amount_due"),
            rs.getLong("principal_due"),
            rs.getLong("interest_due"),
            rs.getString("status"),
            rs.getLong("paid_amount"));

    private static final RowMapper<ItemRow> ITEM_ROW_MAPPER = (rs, i) -> new ItemRow(
            rs.getLong("item_id"),
            rs.getLong("plan_id"),
            rs.getInt("period_no"),
            rs.getDate("due_date").toLocalDate(),
            rs.getLong("amount_due"),
            rs.getLong("principal_due"),
            rs.getLong("interest_due"),
            rs.getString("status"),
            rs.getLong("paid_amount"),
            rs.getLong("paid_interest"),
            rs.getLong("paid_principal"),
            rs.getLong("waived_amount"),
            rs.getInt("version"));

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

    /** 核销用期次快照（含 item_id 与 V13 拆分列），期次顺序（period_no 升序）。 */
    public List<ItemRow> findItemRows(long planId) {
        return jdbc.query(
                "SELECT * FROM core.schedule_items WHERE plan_id = ? ORDER BY period_no", ITEM_ROW_MAPPER, planId);
    }

    public boolean planExistsForLoan(long loanId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM core.repayment_plans WHERE loan_id = ?", Integer.class, loanId);
        return count != null && count > 0;
    }

    /** uk_plan_loan_version：取 version 1 计划（本波次单版本）。 */
    public Optional<Long> findPlanIdByLoanId(long loanId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT plan_id FROM core.repayment_plans WHERE loan_id = ? ORDER BY plan_version DESC LIMIT 1",
                Long.class, loanId);
        return rows.stream().findFirst();
    }

    /**
     * 核销后更新期次：paid_interest / paid_principal 拆分入账，paid_amount 同步，
     * 状态经 {@code ScheduleItemStatus.afterPayment} 判定后传入（唯一写者口径）。乐观锁兜底并发。
     */
    public int updateItemAfterAllocation(long itemId, long paidInterestTotal, long paidPrincipalTotal,
                                         String nextStatus, int version) {
        // 注意：PG UPDATE 的 SET 右侧引用旧行值，paid_amount 必须显式传「新值之和」，不能写表达式
        return jdbc.update(
                "UPDATE core.schedule_items SET paid_interest = ?, paid_principal = ?, "
                        + "paid_amount = ?, status = ?, "
                        + "version = version + 1, updated_at = now() "
                        + "WHERE item_id = ? AND version = ?",
                paidInterestTotal, paidPrincipalTotal, paidInterestTotal + paidPrincipalTotal,
                nextStatus, itemId, version);
    }

    /**
     * 结清关闭期次：实收本金 + 豁免一次落定；状态由调用方按 {@code SettlementCalculator} 关闭结果
     * 传入（有实收 → PAID；全额豁免 → WAIVED——提前结清对未到期期次的合法终态，R04 §3.6）。
     */
    public int updateItemAfterClosure(long itemId, long paidPrincipalTotal, long waivedTotal,
                                      String nextStatus, int version) {
        // 同上：paid_amount 显式传「paid_interest 新值 + paid_principal 新值」（PG SET 旧行值语义）
        return jdbc.update(
                "UPDATE core.schedule_items SET paid_principal = ?, "
                        + "paid_amount = paid_interest + ?, waived_amount = ?, status = ?, "
                        + "version = version + 1, updated_at = now() "
                        + "WHERE item_id = ? AND version = ?",
                paidPrincipalTotal, paidPrincipalTotal, waivedTotal, nextStatus, itemId, version);
    }

    /** 到期批量（W6 唯一写者）：OPEN 且 due_date ≤ bizDate → DUE。返回行数。 */
    public int rollDue(java.time.LocalDate bizDate) {
        return jdbc.update(
                "UPDATE core.schedule_items SET status = 'DUE', version = version + 1, updated_at = now() "
                        + "WHERE status = 'OPEN' AND due_date <= ?",
                Date.valueOf(bizDate));
    }

    /** 到期批量：DUE 且 due_date < bizDate → OVERDUE（逾期按自然日）。返回行数。 */
    public int rollOverdue(java.time.LocalDate bizDate) {
        return jdbc.update(
                "UPDATE core.schedule_items SET status = 'OVERDUE', version = version + 1, updated_at = now() "
                        + "WHERE status = 'DUE' AND due_date < ?",
                Date.valueOf(bizDate));
    }
}
