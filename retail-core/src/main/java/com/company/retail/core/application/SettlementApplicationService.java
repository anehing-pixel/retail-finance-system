package com.company.retail.core.application;

import com.company.platform.exception.BusinessException;
import com.company.platform.idempotency.IdempotencyGuard;
import com.company.platform.idempotency.IdempotencyRecord;
import com.company.retail.core.domain.SettlementCalculator;
import com.company.retail.core.persistence.JdbcIdempotencyStore;
import com.company.retail.core.persistence.JdbcLoanRepository;
import com.company.retail.core.persistence.JdbcPaymentRepository;
import com.company.retail.core.persistence.JdbcRepaymentPlanRepository;
import com.company.retail.domain.exception.RFErrorCode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 结清用例（W6；R09 §3 POST /api/loans/{id}/settle，权限点 SETTLEMENT_CONFIRM）。
 *
 * <p>type：NORMAL（全部结清）/ EARLY（提前结清，C-10：剩余本金 − 减免额 + 手续费；
 * 减免额/手续费为挡板输入）。幂等：IdempotencyGuard + uk_settlement_loan + 债权终态三兜底。</p>
 */
@Service
public class SettlementApplicationService {

    private static final Set<String> TYPES = Set.of("NORMAL", "EARLY");

    private final SettlementTxExecutor txExecutor;
    private final JdbcLoanRepository loanRepository;
    private final JdbcRepaymentPlanRepository planRepository;
    private final JdbcPaymentRepository paymentRepository;
    private final JdbcIdempotencyStore idempotencyStore;
    private final DisbursementTxExecutor.ObjectMapperHolder mapperHolder;
    private final Clock clock;

    public SettlementApplicationService(SettlementTxExecutor txExecutor,
                                        JdbcLoanRepository loanRepository,
                                        JdbcRepaymentPlanRepository planRepository,
                                        JdbcPaymentRepository paymentRepository,
                                        JdbcIdempotencyStore idempotencyStore,
                                        DisbursementTxExecutor.ObjectMapperHolder mapperHolder) {
        this.txExecutor = txExecutor;
        this.loanRepository = loanRepository;
        this.planRepository = planRepository;
        this.paymentRepository = paymentRepository;
        this.idempotencyStore = idempotencyStore;
        this.mapperHolder = mapperHolder;
        this.clock = Clock.systemUTC();
    }

    /** GET /api/loans/{id}/settlement-quote：结清金额试算（C-10 口径，挡板输入可配）。 */
    public SettlementQuoteView quote(long loanId, long waiverAmount, long settlementFee) {
        var loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "债权不存在", Map.of("loanId", String.valueOf(loanId))));
        List<SettlementCalculator.SettleableItem> items = settleableItems(loanId);
        JdbcPaymentRepository.AllocationTotals allocTotals = paymentRepository.sumAllocationsByLoan(loanId);
        long feeOutstanding = loan.feeAmount() - allocTotals.feeTotal();
        long remainingPrincipal = items.stream()
                .mapToLong(SettlementCalculator.SettleableItem::unpaidPrincipal).sum();
        long quote = SettlementCalculator.earlySettlementAmount(remainingPrincipal, waiverAmount, settlementFee);
        return new SettlementQuoteView(loanId, loan.status().name(), remainingPrincipal,
                waiverAmount, settlementFee, feeOutstanding, quote);
    }

    /** POST /api/loans/{id}/settle：结清确认。 */
    public SettlementView settle(SettlementRequest request, String idempotencyKey, String traceId) {
        validate(request);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "Idempotency-Key 必填（R09 §2）");
        }
        String fingerprint = IdempotencyGuard.sha256Fingerprint(canonical(request));
        Optional<IdempotencyRecord> existing = IdempotencyGuard.checkOrThrow(
                idempotencyStore, idempotencyKey, fingerprint, RFErrorCode.RF_CONFLICT_001);
        if (existing.isPresent()) {
            String replay = idempotencyStore.findResponse(idempotencyKey)
                    .orElseThrow(() -> new BusinessException(RFErrorCode.RF_CONFLICT_001,
                            "幂等键已存在但首次执行未完成，请稍后重试"));
            return readReplay(replay);
        }
        SettlementView view = txExecutor.settleInTx(request, traceId);
        idempotencyStore.save(new IdempotencyRecord(idempotencyKey, fingerprint, Instant.now(clock)));
        idempotencyStore.saveResponse(idempotencyKey, mapperHolder.writeJson(view));
        return view;
    }

    private List<SettlementCalculator.SettleableItem> settleableItems(long loanId) {
        Long planId = planRepository.findPlanIdByLoanId(loanId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_002,
                        "债权尚无还款计划，无法结清", Map.of("loanId", String.valueOf(loanId))));
        List<SettlementCalculator.SettleableItem> items = new ArrayList<>();
        for (JdbcRepaymentPlanRepository.ItemRow row : planRepository.findItemRows(planId)) {
            long unpaid = row.amountDue() - row.paidAmount() - row.waivedAmount();
            if (unpaid > 0) {
                items.add(new SettlementCalculator.SettleableItem(row.itemId(), row.periodNo(),
                        row.interestDue(), row.principalDue(), row.paidInterest(), row.paidPrincipal()));
            }
        }
        return items;
    }

    private void validate(SettlementRequest request) {
        if (!TYPES.contains(request.type())) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "type 必须是 NORMAL / EARLY");
        }
        if (request.loanId() <= 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "loanId 必须为正整数业务主键");
        }
        if (request.waiverAmount() < 0 || request.settlementFee() < 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "减免额/手续费必须为非负整数日元");
        }
    }

    private String canonical(SettlementRequest request) {
        return "loanId=%d|type=%s|waiver=%d|fee=%d".formatted(
                request.loanId(), request.type(), request.waiverAmount(), request.settlementFee());
    }

    private SettlementView readReplay(String json) {
        try {
            return mapperHolder.getMapper().readValue(json, SettlementView.class);
        } catch (Exception e) {
            throw new IllegalStateException("重放结果反序列化失败", e);
        }
    }

    /** 结清请求（EARLY 时的减免额/手续费为挡板输入，C-10）。 */
    public record SettlementRequest(long loanId, String type, long waiverAmount, long settlementFee) {
    }

    /** 结清结果视图。 */
    public record SettlementView(long loanId, String status, String settlementType, long quote,
                                 long waiverAmount, long settlementFee, long balanceAfter,
                                 Instant settledAt) {
    }

    /** 结清试算视图（C-10 公式拆项）。 */
    public record SettlementQuoteView(long loanId, String loanStatus, long remainingPrincipal,
                                      long waiverAmount, long settlementFee, long feeOutstanding,
                                      long quote) {
    }
}
