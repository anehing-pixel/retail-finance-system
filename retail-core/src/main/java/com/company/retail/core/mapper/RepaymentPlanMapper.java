package com.company.retail.core.mapper;

import com.company.retail.core.domain.ScheduleItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 还款计划与期次 MyBatis Mapper（表 core.repayment_plans / core.schedule_items，V6 + V13 核销拆分列）。
 * uk_plan_loan_version：一个债权一个计划版本（本波次只落 version 1，改版归后续波次）。
 *
 * <p><b>schedule_items 状态唯一写者（W6）</b>：期次状态迁移只经本 Mapper 的
 * updateItemAfterAllocation / updateItemAfterClosure / rollDue / rollOverdue 四个口径，
 * 禁止散落 SQL 直接 SET status（R00 §5 / R04 §3.6）。</p>
 */
public interface RepaymentPlanMapper {

    @Insert("INSERT INTO core.repayment_plans (loan_id, method, installment_count, first_due_date, "
            + "bonus_month, plan_version, trace_id) "
            + "VALUES (#{loanId}, #{method}, #{installmentCount}, #{firstDueDate}, #{bonusMonth}, 1, #{traceId})")
    @Options(useGeneratedKeys = true, keyProperty = "planId", keyColumn = "plan_id")
    int insertPlan(Map<String, Object> params);

    /** 期次批量落库（一条 INSERT 多组 VALUES，语义等价原 batchUpdate）。 */
    @Insert({"<script>",
            "INSERT INTO core.schedule_items (plan_id, period_no, due_date, amount_due, principal_due, ",
            "interest_due, status, paid_amount, trace_id) VALUES ",
            "<foreach collection='items' item='it' separator=','>",
            "(#{planId}, #{it.periodNo}, #{it.dueDate}, #{it.amountDue}, #{it.principalDue}, ",
            " #{it.interestDue}, #{it.status}, #{it.paidAmount}, #{traceId})",
            "</foreach>",
            "</script>"})
    int insertItems(@Param("planId") long planId, @Param("items") List<ScheduleItem> items,
                    @Param("traceId") String traceId);

    @Select("SELECT period_no, due_date, amount_due, principal_due, interest_due, status, paid_amount "
            + "FROM core.schedule_items WHERE plan_id = #{planId} ORDER BY period_no")
    List<Map<String, Object>> findItems(@Param("planId") long planId);

    /** 核销用期次快照（含 item_id 与 V13 拆分列），期次顺序（period_no 升序）。 */
    @Select("SELECT item_id, plan_id, period_no, due_date, amount_due, principal_due, interest_due, "
            + "status, paid_amount, paid_interest, paid_principal, waived_amount, version "
            + "FROM core.schedule_items WHERE plan_id = #{planId} ORDER BY period_no")
    List<Map<String, Object>> findItemRows(@Param("planId") long planId);

    @Select("SELECT COUNT(*) FROM core.repayment_plans WHERE loan_id = #{loanId}")
    int countPlansByLoan(@Param("loanId") long loanId);

    /** uk_plan_loan_version：取 version 1 计划（本波次单版本）。 */
    @Select("SELECT plan_id FROM core.repayment_plans WHERE loan_id = #{loanId} "
            + "ORDER BY plan_version DESC LIMIT 1")
    List<Long> findPlanIdByLoanId(@Param("loanId") long loanId);

    /**
     * 核销后更新期次：paid_interest / paid_principal 拆分入账，paid_amount 同步，
     * 状态经 {@code ScheduleItemStatus.afterPayment} 判定后传入（唯一写者口径）。乐观锁兜底并发。
     */
    @Update("UPDATE core.schedule_items SET paid_interest = #{paidInterestTotal}, "
            + "paid_principal = #{paidPrincipalTotal}, paid_amount = #{paidAmountTotal}, "
            + "status = #{nextStatus}, version = version + 1, updated_at = now() "
            + "WHERE item_id = #{itemId} AND version = #{version}")
    int updateItemAfterAllocation(@Param("itemId") long itemId,
                                   @Param("paidInterestTotal") long paidInterestTotal,
                                   @Param("paidPrincipalTotal") long paidPrincipalTotal,
                                   @Param("paidAmountTotal") long paidAmountTotal,
                                   @Param("nextStatus") String nextStatus,
                                   @Param("version") int version);

    /**
     * 结清关闭期次：实收本金 + 豁免一次落定；状态由调用方按 {@code SettlementCalculator} 关闭结果
     * 传入（有实收 → PAID；全额豁免 → WAIVED——提前结清对未到期期次的合法终态，R04 §3.6）。
     */
    @Update("UPDATE core.schedule_items SET paid_principal = #{paidPrincipalTotal}, "
            + "paid_amount = paid_interest + #{paidAmountTotal}, waived_amount = #{waivedTotal}, "
            + "status = #{nextStatus}, version = version + 1, updated_at = now() "
            + "WHERE item_id = #{itemId} AND version = #{version}")
    int updateItemAfterClosure(@Param("itemId") long itemId,
                                @Param("paidPrincipalTotal") long paidPrincipalTotal,
                                @Param("paidAmountTotal") long paidAmountTotal,
                                @Param("waivedTotal") long waivedTotal,
                                @Param("nextStatus") String nextStatus,
                                @Param("version") int version);

    /** 到期批量（W6 唯一写者）：OPEN 且 due_date ≤ bizDate → DUE。返回行数。 */
    @Update("UPDATE core.schedule_items SET status = 'DUE', version = version + 1, updated_at = now() "
            + "WHERE status = 'OPEN' AND due_date <= #{bizDate}")
    int rollDue(@Param("bizDate") LocalDate bizDate);

    /** 到期批量：DUE 且 due_date < bizDate → OVERDUE（逾期按自然日）。返回行数。 */
    @Update("UPDATE core.schedule_items SET status = 'OVERDUE', version = version + 1, updated_at = now() "
            + "WHERE status = 'DUE' AND due_date < #{bizDate}")
    int rollOverdue(@Param("bizDate") LocalDate bizDate);
}
