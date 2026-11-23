package com.company.retail.core.persistence;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.core.mapper.PaymentMapper;
import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code core.payments} / {@code core.payment_allocations} 持久化（V7 表；权威写者 BC-7 retail-core）。
 *
 * <p>不变式：uk_payment_account_txn（X-6 重复入金明细去重）、uk_alloc_payment（一笔入金只核销一次）。</p>
 */
@Repository
public class PaymentRepository {

    /** 入金行（GET / 核销编排用）。 */
    public record PaymentRow(long paymentId, String paymentRef, String accountRef, String transactionId,
                             long amount, Timestamp receivedAt, String channel, String status, int version) {
    }

    /** 核销汇总（对拍流水侧：Σ 费用/利息/本金 已核销）。 */
    public record AllocationTotals(long feeTotal, long interestTotal, long principalTotal) {
        public long grandTotal() {
            return feeTotal + interestTotal + principalTotal;
        }
    }

    private final PaymentMapper mapper;

    public PaymentRepository(PaymentMapper mapper) {
        this.mapper = mapper;
    }

    /** 入账（status=RECEIVED）；uk_payment_account_txn 兜底重复明细（X-6）。 */
    public long insert(String paymentRef, String accountRef, String transactionId, long amount,
                       Timestamp receivedAt, String channel, String status, String traceId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paymentRef", paymentRef);
        params.put("accountRef", accountRef);
        params.put("transactionId", transactionId);
        params.put("amount", amount);
        params.put("receivedAt", receivedAt);
        params.put("channel", channel);
        params.put("status", status);
        params.put("traceId", traceId);
        try {
            mapper.insert(params);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(RFErrorCode.RF_PAY_001,
                    "入金明细重复（uk_payment_account_txn，X-6 去重）");
        }
        Object id = params.get("paymentId");
        return id == null ? 0L : ((Number) id).longValue();
    }

    public Optional<PaymentRow> findById(long paymentId) {
        List<Map<String, Object>> rows = mapper.findById(paymentId);
        return rows.stream().findFirst().map(PaymentRepository::toRow);
    }

    /** 核销记录（uk_alloc_payment：一笔入金只核销一次；schedule_item_id 汇总口径置 NULL）。 */
    public long insertAllocation(long paymentId, long loanId, Long scheduleItemId,
                                 long feeAmount, long interestAmount, long principalAmount,
                                 String traceId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paymentId", paymentId);
        params.put("loanId", loanId);
        params.put("scheduleItemId", scheduleItemId);
        params.put("feeAmount", feeAmount);
        params.put("interestAmount", interestAmount);
        params.put("principalAmount", principalAmount);
        params.put("traceId", traceId);
        try {
            mapper.insertAllocation(params);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001,
                    "重复核销（uk_alloc_payment：一笔入金只核销一次）");
        }
        Object id = params.get("allocationId");
        return id == null ? 0L : ((Number) id).longValue();
    }

    /** 流水侧核销汇总（对拍 ledgerBalance 与费用未清偿口径）。 */
    public AllocationTotals sumAllocationsByLoan(long loanId) {
        List<Map<String, Object>> rows = mapper.sumAllocationsByLoan(loanId);
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
        return new AllocationTotals(RowValues.asLong(row, "fee_total"),
                RowValues.asLong(row, "interest_total"), RowValues.asLong(row, "principal_total"));
    }

    /** 状态迁移（RECEIVED → MATCHED/UNMATCHED → ALLOCATED；R04 §3.6 PaymentStatus）。 */
    public int updateStatus(long paymentId, String status) {
        return mapper.updateStatus(paymentId, status);
    }

    private static PaymentRow toRow(Map<String, Object> row) {
        return new PaymentRow(
                RowValues.asLong(row, "payment_id"),
                RowValues.asString(row, "payment_ref"),
                RowValues.asString(row, "account_ref"),
                RowValues.asString(row, "transaction_id"),
                RowValues.asLong(row, "amount"),
                RowValues.asTimestamp(row, "received_at"),
                RowValues.asString(row, "channel"),
                RowValues.asString(row, "status"),
                RowValues.asInt(row, "version"));
    }
}
