package com.company.retail.core.application;

import com.company.platform.exception.BusinessException;
import com.company.platform.idempotency.IdempotencyGuard;
import com.company.platform.idempotency.IdempotencyRecord;
import com.company.retail.core.persistence.MyBatisIdempotencyStore;
import com.company.retail.core.persistence.PaymentRepository;
import com.company.retail.domain.exception.RFErrorCode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 入金核销用例（W6：收款入账 → 按期次顺序核销 → 更新期次与债权余额）。
 *
 * <p>幂等（R09 §6：入金核销按 paymentRef）：① {@link IdempotencyGuard}（键同体不同 → RF_CONFLICT_001）
 * ② uk_payment_account_txn（重复明细去重 X-6）③ uk_alloc_payment（一笔入金只核销一次）。</p>
 *
 * <p>无 loanId 的入金 → UNMATCHED（X-7 人工匹配工作台，不自动猜测分摊）；
 * {@code POST /api/payments/{id}/manual-match}（PAYMENT_MANUAL_MATCH）落核销。</p>
 */
@Service
public class PaymentApplicationService {

    private static final Set<String> CHANNELS = Set.of(
            "DIRECT_DEBIT", "PAYMENT_SLIP", "CONVENIENCE_STORE", "BANK_TRANSFER");

    private final PaymentTxExecutor txExecutor;
    private final MyBatisIdempotencyStore idempotencyStore;
    private final PaymentRepository paymentRepository;
    private final DisbursementTxExecutor.ObjectMapperHolder mapperHolder;
    private final Clock clock;

    public PaymentApplicationService(PaymentTxExecutor txExecutor,
                                     MyBatisIdempotencyStore idempotencyStore,
                                     PaymentRepository paymentRepository,
                                     DisbursementTxExecutor.ObjectMapperHolder mapperHolder) {
        this.txExecutor = txExecutor;
        this.idempotencyStore = idempotencyStore;
        this.paymentRepository = paymentRepository;
        this.mapperHolder = mapperHolder;
        this.clock = Clock.systemUTC();
    }

    /** POST /api/payments：入金录入 + 自动核销（loanId > 0）或转人工匹配（loanId 缺省）。 */
    public PaymentView allocate(PaymentRequest request, String idempotencyKey, String traceId) {
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

        String paymentRef = request.paymentRef() == null || request.paymentRef().isBlank()
                ? "PM-" + request.accountRef() + "-" + request.transactionId()
                : request.paymentRef();

        PaymentView view;
        if (request.loanId() <= 0) {
            // X-7：无法匹配合同 → UNMATCHED 人工工作台，不自动猜测分摊
            view = createUnmatched(request, paymentRef, traceId);
        } else {
            view = txExecutor.allocateInTx(request, null, paymentRef, traceId);
        }

        idempotencyStore.save(new IdempotencyRecord(idempotencyKey, fingerprint, Instant.now(clock)));
        idempotencyStore.saveResponse(idempotencyKey, mapperHolder.writeJson(view));
        return view;
    }

    /** POST /api/payments/{id}/manual-match：UNMATCHED 入金人工匹配到债权并核销。 */
    public PaymentView manualMatch(long paymentId, long loanId, String traceId) {
        if (loanId <= 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "loanId 必须为正整数业务主键");
        }
        PaymentRepository.PaymentRow payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "入金记录不存在", Map.of("paymentId", String.valueOf(paymentId))));
        if (!"UNMATCHED".equals(payment.status())) {
            throw new BusinessException(RFErrorCode.RF_VALID_002,
                    "入金状态不允许人工匹配（仅 UNMATCHED）",
                    Map.of("status", payment.status()));
        }
        PaymentRequest request = new PaymentRequest(loanId, payment.accountRef(),
                payment.transactionId(), payment.amount(), payment.channel(), payment.paymentRef());
        return txExecutor.allocateInTx(request, paymentId, payment.paymentRef(), traceId);
    }

    public PaymentView get(long paymentId) {
        PaymentRepository.PaymentRow row = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "入金记录不存在", Map.of("paymentId", String.valueOf(paymentId))));
        return new PaymentView(row.paymentId(), row.paymentRef(), 0, row.status(),
                row.amount(), 0, 0, 0, 0, row.status());
    }

    private PaymentView createUnmatched(PaymentRequest request, String paymentRef, String traceId) {
        long paymentId = paymentRepository.insert(paymentRef, request.accountRef(),
                request.transactionId(), request.amount(),
                java.sql.Timestamp.from(Instant.now(clock)), request.channel(), "UNMATCHED", traceId);
        return new PaymentView(paymentId, paymentRef, 0, "UNMATCHED", request.amount(),
                0, 0, 0, 0, "UNMATCHED");
    }

    private void validate(PaymentRequest request) {
        if (request.accountRef() == null || request.accountRef().isBlank()
                || request.transactionId() == null || request.transactionId().isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "accountRef / transactionId 必填");
        }
        if (request.amount() <= 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "amount 必须为正整数日元");
        }
        if (request.channel() == null || !CHANNELS.contains(request.channel())) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    "channel 必须是 DIRECT_DEBIT / PAYMENT_SLIP / CONVENIENCE_STORE / BANK_TRANSFER");
        }
    }

    /** 请求指纹（规范化内容；幂等重放判定依据）。 */
    private String canonical(PaymentRequest request) {
        return "loanId=%d|accountRef=%s|transactionId=%s|amount=%d|channel=%s".formatted(
                request.loanId(), request.accountRef(), request.transactionId(),
                request.amount(), request.channel());
    }

    private PaymentView readReplay(String json) {
        try {
            return mapperHolder.getMapper().readValue(json, PaymentView.class);
        } catch (Exception e) {
            throw new IllegalStateException("重放结果反序列化失败", e);
        }
    }

    /** 入金请求（loanId ≤ 0 → UNMATCHED）。 */
    public record PaymentRequest(long loanId, String accountRef, String transactionId,
                                 long amount, String channel, String paymentRef) {
    }

    /** 核销结果视图。 */
    public record PaymentView(long paymentId, String paymentRef, long loanId, String status,
                              long amount, long allocatedFee, long allocatedInterest,
                              long allocatedPrincipal, long loanBalanceAfter, String loanStatusAfter) {
    }
}
