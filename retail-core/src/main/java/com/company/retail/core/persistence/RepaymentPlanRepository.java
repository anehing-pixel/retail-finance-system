package com.company.retail.core.persistence;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.core.domain.ScheduleItem;
import com.company.retail.core.mapper.RepaymentPlanMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
public class RepaymentPlanRepository {

    /** 期次核销快照（W6 核销/结清/对拍的行级视图，含 V13 拆分列）。 */
    public record ItemRow(long itemId, long planId, int periodNo, LocalDate dueDate,
                          long amountDue, long principalDue, long interestDue,
                          String status, long paidAmount, long paidInterest,
                          long paidPrincipal, long waivedAmount, int version) {
    }

    private final RepaymentPlanMapper mapper;

    public RepaymentPlanRepository(RepaymentPlanMapper mapper) {
        this.mapper = mapper;
    }

    public long insertPlan(long loanId, String method, int installmentCount, LocalDate firstDueDate,
                           Integer bonusMonth, String productCode, Integer productVersion, int rateBp,
                           String traceId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("loanId", loanId);
        params.put("method", method);
        params.put("installmentCount", installmentCount);
        params.put("firstDueDate", firstDueDate);
        params.put("bonusMonth", bonusMonth);
        // 快照三件套：档位码 / 档位版本 / 实际采用的利率（配置变更不影响历史计划）
        params.put("productCode", productCode);
        params.put("productVersion", productVersion);
        params.put("rateBp", rateBp);
        params.put("traceId", traceId);
        mapper.insertPlan(params);
        Object id = params.get("planId");
        return id == null ? 0L : ((Number) id).longValue();
    }

    public void insertItems(long planId, List<ScheduleItem> items, String traceId) {
        mapper.insertItems(planId, items, traceId);
    }

    public List<ScheduleItem> findItems(long planId) {
        List<Map<String, Object>> rows = mapper.findItems(planId);
        return rows.stream().map(RepaymentPlanRepository::toItem).toList();
    }

    /** 核销用期次快照（含 item_id 与 V13 拆分列），期次顺序（period_no 升序）。 */
    public List<ItemRow> findItemRows(long planId) {
        List<Map<String, Object>> rows = mapper.findItemRows(planId);
        return rows.stream().map(RepaymentPlanRepository::toItemRow).toList();
    }

    public boolean planExistsForLoan(long loanId) {
        return mapper.countPlansByLoan(loanId) > 0;
    }

    /** uk_plan_loan_version：取 version 1 计划（本波次单版本）。 */
    public Optional<Long> findPlanIdByLoanId(long loanId) {
        List<Long> rows = mapper.findPlanIdByLoanId(loanId);
        return rows.stream().findFirst();
    }

    /**
     * 核销后更新期次：paid_interest / paid_principal 拆分入账，paid_amount 同步，
     * 状态经 {@code ScheduleItemStatus.afterPayment} 判定后传入（唯一写者口径）。乐观锁兜底并发。
     */
    public int updateItemAfterAllocation(long itemId, long paidInterestTotal, long paidPrincipalTotal,
                                         String nextStatus, int version) {
        // 注意：PG UPDATE 的 SET 右侧引用旧行值，paid_amount 必须显式传「新值之和」，不能写表达式
        return mapper.updateItemAfterAllocation(itemId, paidInterestTotal, paidPrincipalTotal,
                paidInterestTotal + paidPrincipalTotal, nextStatus, version);
    }

    /**
     * 结清关闭期次：实收本金 + 豁免一次落定；状态由调用方按 {@code SettlementCalculator} 关闭结果
     * 传入（有实收 → PAID；全额豁免 → WAIVED——提前结清对未到期期次的合法终态，R04 §3.6）。
     */
    public int updateItemAfterClosure(long itemId, long paidPrincipalTotal, long waivedTotal,
                                      String nextStatus, int version) {
        // 同上：paid_amount 显式传「paid_interest 新值 + paid_principal 新值」（PG SET 旧行值语义）
        return mapper.updateItemAfterClosure(itemId, paidPrincipalTotal, paidPrincipalTotal,
                waivedTotal, nextStatus, version);
    }

    /** 到期批量（W6 唯一写者）：OPEN 且 due_date ≤ bizDate → DUE。返回行数。 */
    public int rollDue(LocalDate bizDate) {
        return mapper.rollDue(bizDate);
    }

    /** 到期批量：DUE 且 due_date < bizDate → OVERDUE（逾期按自然日）。返回行数。 */
    public int rollOverdue(LocalDate bizDate) {
        return mapper.rollOverdue(bizDate);
    }

    private static ScheduleItem toItem(Map<String, Object> row) {
        return new ScheduleItem(
                RowValues.asInt(row, "period_no"),
                RowValues.asLocalDate(row, "due_date"),
                RowValues.asLong(row, "amount_due"),
                RowValues.asLong(row, "principal_due"),
                RowValues.asLong(row, "interest_due"),
                RowValues.asString(row, "status"),
                RowValues.asLong(row, "paid_amount"));
    }

    private static ItemRow toItemRow(Map<String, Object> row) {
        return new ItemRow(
                RowValues.asLong(row, "item_id"),
                RowValues.asLong(row, "plan_id"),
                RowValues.asInt(row, "period_no"),
                RowValues.asLocalDate(row, "due_date"),
                RowValues.asLong(row, "amount_due"),
                RowValues.asLong(row, "principal_due"),
                RowValues.asLong(row, "interest_due"),
                RowValues.asString(row, "status"),
                RowValues.asLong(row, "paid_amount"),
                RowValues.asLong(row, "paid_interest"),
                RowValues.asLong(row, "paid_principal"),
                RowValues.asLong(row, "waived_amount"),
                RowValues.asInt(row, "version"));
    }
}
