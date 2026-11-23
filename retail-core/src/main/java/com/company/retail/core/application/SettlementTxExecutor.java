package com.company.retail.core.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.core.domain.Loan;
import com.company.retail.core.domain.ScheduleItemStatus;
import com.company.retail.core.domain.SettlementCalculator;
import com.company.retail.core.persistence.LoanRepository;
import com.company.retail.core.persistence.PaymentRepository;
import com.company.retail.core.persistence.RepaymentPlanRepository;
import com.company.retail.core.persistence.SettlementAdjustmentRepository;
import com.company.retail.domain.exception.RFErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结清本地事务执行器（G10；C-10 公式；R05 §7 提前结清分支）。
 *
 * <p><b>全部结清（NORMAL）</b>：末回核销完成、余额精确为 0 → SETTLED（B-7 终态之一）。</p>
 * <p><b>提前结清（EARLY）</b>：quote = 剩余本金 − 减免额 + 手续费（C-10）；同一本地事务内：
 * 手续费计提 → 结清入金核销 → 费用/未到期利息/减免本金豁免（settlement_adjustments 流水）→
 * 期次关闭（实收 PAID / 全额豁免 WAIVED）→ 余额归零断言 → EARLY_SETTLED → LOAN_SETTLED。</p>
 */
@Component
public class SettlementTxExecutor {

    private static final Logger log = LoggerFactory.getLogger(SettlementTxExecutor.class);

    private final LoanRepository loanRepository;
    private final RepaymentPlanRepository planRepository;
    private final PaymentRepository paymentRepository;
    private final SettlementAdjustmentRepository adjustmentRepository;
    private final ReconciliationApplicationService reconciliation;
    private final OutboxPublisher outbox;
    private final DisbursementTxExecutor.ObjectMapperHolder mapperHolder;

    public SettlementTxExecutor(LoanRepository loanRepository,
                                RepaymentPlanRepository planRepository,
                                PaymentRepository paymentRepository,
                                SettlementAdjustmentRepository adjustmentRepository,
                                ReconciliationApplicationService reconciliation,
                                OutboxPublisher outbox,
                                DisbursementTxExecutor.ObjectMapperHolder mapperHolder) {
        this.loanRepository = loanRepository;
        this.planRepository = planRepository;
        this.paymentRepository = paymentRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.reconciliation = reconciliation;
        this.outbox = outbox;
        this.mapperHolder = mapperHolder;
    }

    @Transactional
    public SettlementApplicationService.SettlementView settleInTx(
            SettlementApplicationService.SettlementRequest request, String traceId) {
        boolean early = "EARLY".equals(request.type());
        Instant settledAt = Instant.now();

        Loan loan = loanRepository.findByIdForUpdate(request.loanId())
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "债权不存在", Map.of("loanId", String.valueOf(request.loanId()))));

        long quote;
        if (!early) {
            // 全部结清：G10 —— 末回核销完成、无未清偿期次、余额精确为 0
            assertNoUnpaidItems(request.loanId());
            loan.assertSettlementAllowed();
            quote = 0;
            loan.settle(false, settledAt);
        } else {
            // 提前结清（C-10）：剩余本金 − 减免额 + 手续费
            List<RepaymentPlanRepository.ItemRow> unpaidRows = loadUnpaidRows(request.loanId());
            List<SettlementCalculator.SettleableItem> items = new ArrayList<>();
            for (RepaymentPlanRepository.ItemRow row : unpaidRows) {
                items.add(new SettlementCalculator.SettleableItem(row.itemId(), row.periodNo(),
                        row.interestDue(), row.principalDue(), row.paidInterest(), row.paidPrincipal()));
            }
            PaymentRepository.AllocationTotals allocTotals =
                    paymentRepository.sumAllocationsByLoan(request.loanId());
            long feeOutstanding = loan.feeAmount() - allocTotals.feeTotal();

            SettlementCalculator.ClosurePlan closure = SettlementCalculator.planClosure(
                    items, request.waiverAmount(), request.settlementFee(), feeOutstanding);
            quote = closure.principalPaidTotal() + request.settlementFee();

            // ① 手续费计提 → ② 结清入金 → ③ 豁免（三步余额侧合计精确归零）
            loan.levySettlementFee(request.settlementFee());
            loan.applyAllocation(quote);
            loan.applyWaiver(closure.waivedTotal());
            loan.recordSettlementTerms(request.waiverAmount(), request.settlementFee());
            loan.assertSettlementAllowed();
            loan.settle(true, settledAt);

            // ④ 结清入金（uk_payment_account_txn：account_ref=SETTLEMENT + transaction_id=STL-<loanId> 唯一）
            long paymentId;
            try {
                paymentId = paymentRepository.insert("PAY-STL-" + request.loanId(), "SETTLEMENT",
                        "STL-" + request.loanId(), quote, Timestamp.from(settledAt),
                        "BANK_TRANSFER", "ALLOCATED", traceId);
            } catch (BusinessException e) {
                throw new BusinessException(RFErrorCode.RF_CONFLICT_001, "重复结清入金（唯一约束兜底）");
            }
            paymentRepository.insertAllocation(paymentId, request.loanId(), null,
                    request.settlementFee(), 0, closure.principalPaidTotal(), traceId);

            // ⑤ 结清调整流水（uk_settlement_loan 幂等锚点；INV-2 豁免侧）
            adjustmentRepository.insert(request.loanId(), "EARLY", closure.principalWaiver(),
                    closure.interestWaiver(), closure.feeWaiver(), request.settlementFee(), traceId);

            // ⑥ 期次关闭（实收 → PAID；全额豁免 → WAIVED）
            closeItems(unpaidRows, closure);
        }

        if (loanRepository.updateAfterSettlement(loan) == 0) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001, "债权并发更新冲突（乐观锁 version）");
        }
        reconciliation.assertConsistent(request.loanId());

        // LOAN_SETTLED（聚合键 loanId；payload 只带业务主键，INV-4；消费方 title-release-service）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", request.loanId());
        payload.put("contractId", loan.contractId());
        payload.put("status", loan.status().name());
        payload.put("amount", quote);
        payload.put("currency", "JPY");
        payload.put("traceId", traceId);
        outbox.publish(OutboxPublisher.LOAN_SETTLED, String.valueOf(request.loanId()),
                mapperHolder.writeJson(payload));

        log.info("结清完成 loanId={} type={} quote={} status={}",
                request.loanId(), request.type(), quote, loan.status().name());
        return new SettlementApplicationService.SettlementView(request.loanId(),
                loan.status().name(), loan.settlementType(), quote,
                request.waiverAmount(), request.settlementFee(), loan.balance(), settledAt);
    }

    private void assertNoUnpaidItems(long loanId) {
        List<RepaymentPlanRepository.ItemRow> rows = unpaidRows(loanId);
        long unpaid = rows.stream()
                .mapToLong(r -> r.amountDue() - r.paidAmount() - r.waivedAmount())
                .sum();
        if (unpaid != 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_002,
                    "存在未核销期次，不得进入全部结清（G10：末回核销前置）",
                    Map.of("unpaidTotal", String.valueOf(unpaid)));
        }
        if (rows.isEmpty() && !planRepository.findPlanIdByLoanId(loanId).isPresent()) {
            throw new BusinessException(RFErrorCode.RF_VALID_002,
                    "债权尚无还款计划，无法确认末回核销（G10）");
        }
    }

    private List<RepaymentPlanRepository.ItemRow> loadUnpaidRows(long loanId) {
        List<RepaymentPlanRepository.ItemRow> rows = unpaidRows(loanId);
        if (rows.isEmpty()) {
            throw new BusinessException(RFErrorCode.RF_VALID_002,
                    "无未清偿期次，应走全部结清（NORMAL）而非提前结清");
        }
        return rows;
    }

    private List<RepaymentPlanRepository.ItemRow> unpaidRows(long loanId) {
        Long planId = planRepository.findPlanIdByLoanId(loanId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_002,
                        "债权尚无还款计划，无法结清", Map.of("loanId", String.valueOf(loanId))));
        List<RepaymentPlanRepository.ItemRow> rows = planRepository.findItemRows(planId);
        List<RepaymentPlanRepository.ItemRow> unpaid = new ArrayList<>();
        for (RepaymentPlanRepository.ItemRow row : rows) {
            ScheduleItemStatus st = ScheduleItemStatus.valueOf(row.status());
            if (!st.isTerminal() && row.amountDue() - row.paidAmount() - row.waivedAmount() > 0) {
                unpaid.add(row);
            }
        }
        return unpaid;
    }

    private void closeItems(List<RepaymentPlanRepository.ItemRow> rows,
                            SettlementCalculator.ClosurePlan closure) {
        Map<Long, SettlementCalculator.ItemClosure> byItem = new LinkedHashMap<>();
        closure.items().forEach(c -> byItem.put(c.itemId(), c));
        for (RepaymentPlanRepository.ItemRow row : rows) {
            SettlementCalculator.ItemClosure c = byItem.get(row.itemId());
            if (c == null) {
                continue;
            }
            String nextStatus = c.principalPaid() > 0
                    ? ScheduleItemStatus.PAID.name() : ScheduleItemStatus.WAIVED.name();
            if (planRepository.updateItemAfterClosure(row.itemId(),
                    row.paidPrincipal() + c.principalPaid(), row.waivedAmount() + c.waived(),
                    nextStatus, row.version()) == 0) {
                throw new BusinessException(RFErrorCode.RF_CONFLICT_001,
                        "期次并发更新冲突（乐观锁 version）",
                        Map.of("itemId", String.valueOf(row.itemId())));
            }
        }
    }
}
