package com.company.retail.core.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.core.domain.Loan;
import com.company.retail.core.domain.PaymentAllocationPlanner;
import com.company.retail.core.domain.ScheduleItemStatus;
import com.company.retail.core.persistence.JdbcLoanRepository;
import com.company.retail.core.persistence.JdbcPaymentRepository;
import com.company.retail.core.persistence.JdbcRepaymentPlanRepository;
import com.company.retail.domain.exception.RFErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入金核销本地事务执行器（R05 §5 S9–S10：核销 + 余额 + 状态迁移同一本地事务，P-4 账务红线）。
 *
 * <p>顺序（{@code PaymentAllocationPlanner}）：費用 → 期次顺序内先息后本；余额更新与
 * 期次状态变更、REPAYMENT_RECEIVED 事件写同一事务；事务尾做余额对拍断言（INV-2）。</p>
 */
@Component
public class PaymentTxExecutor {

    private static final Logger log = LoggerFactory.getLogger(PaymentTxExecutor.class);

    private final JdbcLoanRepository loanRepository;
    private final JdbcRepaymentPlanRepository planRepository;
    private final JdbcPaymentRepository paymentRepository;
    private final ReconciliationApplicationService reconciliation;
    private final OutboxPublisher outbox;
    private final DisbursementTxExecutor.ObjectMapperHolder mapperHolder;

    public PaymentTxExecutor(JdbcLoanRepository loanRepository,
                             JdbcRepaymentPlanRepository planRepository,
                             JdbcPaymentRepository paymentRepository,
                             ReconciliationApplicationService reconciliation,
                             OutboxPublisher outbox,
                             DisbursementTxExecutor.ObjectMapperHolder mapperHolder) {
        this.loanRepository = loanRepository;
        this.planRepository = planRepository;
        this.paymentRepository = paymentRepository;
        this.reconciliation = reconciliation;
        this.outbox = outbox;
        this.mapperHolder = mapperHolder;
    }

    /**
     * 核销执行。
     *
     * @param existingPaymentId 非空 = 人工匹配已存在的 UNMATCHED 入金；空 = 先入账再核销
     */
    @Transactional
    public PaymentApplicationService.PaymentView allocateInTx(
            PaymentApplicationService.PaymentRequest request, Long existingPaymentId,
            String paymentRef, String traceId) {
        // ① 入金入账（RECEIVED → ALLOCATED 生命周期在本事务内完成；uk_payment_account_txn 去重 X-6）
        long paymentId = existingPaymentId == null
                ? paymentRepository.insert(paymentRef, request.accountRef(), request.transactionId(),
                request.amount(), java.sql.Timestamp.from(java.time.Instant.now()),
                request.channel(), "ALLOCATED", traceId)
                : existingPaymentId;
        paymentRepository.updateStatus(paymentId, "ALLOCATED");

        // ② 债权行锁 + 守卫（终态/在途代付不可核销）
        Loan loan = loanRepository.findByIdForUpdate(request.loanId())
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "债权不存在", Map.of("loanId", String.valueOf(request.loanId()))));
        loan.assertAllocationAllowed();

        // ③ 计划 + 可核销期次（状态 ∈ {DUE, PARTIAL, OVERDUE}；OPEN 未到期不可核销）
        Long planId = planRepository.findPlanIdByLoanId(request.loanId())
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_PAY_001,
                        "债权尚无还款计划，入金无法核销", Map.of("loanId", String.valueOf(request.loanId()))));
        JdbcPaymentRepository.AllocationTotals allocTotals = paymentRepository.sumAllocationsByLoan(request.loanId());
        long feeOutstanding = loan.feeAmount() - allocTotals.feeTotal();

        List<JdbcRepaymentPlanRepository.ItemRow> rows = planRepository.findItemRows(planId);
        List<PaymentAllocationPlanner.AllocatableItem> allocatable = new ArrayList<>();
        for (JdbcRepaymentPlanRepository.ItemRow row : rows) {
            ScheduleItemStatus st = ScheduleItemStatus.valueOf(row.status());
            PaymentAllocationPlanner.AllocatableItem item = new PaymentAllocationPlanner.AllocatableItem(
                    row.itemId(), row.periodNo(), st, row.interestDue(), row.principalDue(),
                    row.paidInterest(), row.paidPrincipal());
            if (st.isAllocatable() && item.unpaidTotal() > 0) {
                allocatable.add(item);
            }
        }

        // ④ 拆分（費用→期次顺序先息后本）；超额 → X-8 拒绝
        PaymentAllocationPlanner.AllocationPlan plan =
                PaymentAllocationPlanner.plan(feeOutstanding, allocatable, request.amount());
        if (plan.remainder() > 0) {
            throw new BusinessException(RFErrorCode.RF_PAY_002,
                    "超额入金（X-8：转工单/退款，不自动分摊）",
                    Map.of("remainder", String.valueOf(plan.remainder())));
        }

        // ⑤ 余额扣减 + 逾期补救（DELINQUENT → ACTIVE）
        long totalAllocated = plan.feePart() + plan.interestTotal() + plan.principalTotal();
        loan.applyAllocation(totalAllocated);
        if (loan.status() == com.company.retail.core.domain.LoanStatus.DELINQUENT) {
            loan.cureFromDelinquent();
        }

        // ⑥ 核销记录（uk_alloc_payment 一笔入金只核销一次）
        paymentRepository.insertAllocation(paymentId, request.loanId(), null,
                plan.feePart(), plan.interestTotal(), plan.principalTotal(), traceId);

        // ⑦ 期次状态收编（W6 唯一写者）：先息后本拆分入账 + 状态机判定
        Map<Long, PaymentAllocationPlanner.ItemAllocation> byItem = new LinkedHashMap<>();
        plan.items().forEach(a -> byItem.put(a.itemId(), a));
        for (JdbcRepaymentPlanRepository.ItemRow row : rows) {
            PaymentAllocationPlanner.ItemAllocation a = byItem.get(row.itemId());
            if (a == null) {
                continue;
            }
            long paidInterestTotal = row.paidInterest() + a.interestPart();
            long paidPrincipalTotal = row.paidPrincipal() + a.principalPart();
            ScheduleItemStatus current = ScheduleItemStatus.valueOf(row.status());
            long unpaidBefore = row.interestDue() - row.paidInterest()
                    + row.principalDue() - row.paidPrincipal();
            long paidNow = a.interestPart() + a.principalPart();
            ScheduleItemStatus next = ScheduleItemStatus.afterPayment(current, unpaidBefore, paidNow);
            if (planRepository.updateItemAfterAllocation(row.itemId(), paidInterestTotal,
                    paidPrincipalTotal, next.name(), row.version()) == 0) {
                throw new BusinessException(RFErrorCode.RF_CONFLICT_001,
                        "期次并发更新冲突（乐观锁 version）",
                        Map.of("itemId", String.valueOf(row.itemId())));
            }
        }

        // ⑧ 债权余额/状态乐观锁更新
        if (loanRepository.updateAfterAllocation(loan) == 0) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001, "债权并发更新冲突（乐观锁 version）");
        }

        // ⑨ 余额对拍断言（INV-2 内部断言；失败抛 RF_SYS_001 让事务回滚）
        reconciliation.assertConsistent(request.loanId());

        // ⑩ REPAYMENT_RECEIVED（聚合键 paymentRef；payload 白名单只带业务主键，INV-4）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentRef", paymentRef);
        payload.put("loanId", request.loanId());
        payload.put("amount", totalAllocated);
        payload.put("currency", "JPY");
        payload.put("status", "ALLOCATED");
        payload.put("traceId", traceId);
        outbox.publish(OutboxPublisher.REPAYMENT_RECEIVED, paymentRef, mapperHolder.writeJson(payload));

        log.info("入金核销完成 loanId={} paymentRef={} fee={} interest={} principal={} balance={}",
                request.loanId(), paymentRef, plan.feePart(), plan.interestTotal(),
                plan.principalTotal(), loan.balance());
        return new PaymentApplicationService.PaymentView(paymentId, paymentRef, request.loanId(),
                "ALLOCATED", request.amount(), plan.feePart(), plan.interestTotal(),
                plan.principalTotal(), loan.balance(), loan.status().name());
    }
}
