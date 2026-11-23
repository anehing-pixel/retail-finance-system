package com.company.retail.core.application;

import com.company.platform.exception.BusinessException;
import com.company.platform.idempotency.IdempotencyGuard;
import com.company.platform.idempotency.IdempotencyRecord;
import com.company.retail.core.integration.DisbursementGateChecker;
import com.company.retail.core.persistence.MyBatisIdempotencyStore;
import com.company.retail.domain.exception.RFErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * 代付用例编排（W5 前半）：幂等守卫 → G8 门禁（fail-closed）→ 本地事务执行。
 *
 * <p><b>幂等三层</b>（R09 §6）：① {@link IdempotencyGuard}（指纹同 → 重放返回首次结果，
 * 指纹异 → {@code RF_CONFLICT_001}）② uk_disb_idempotency ③ uk_disb_loan（INV-1）。
 * 事务边界在 {@link DisbursementTxExecutor}（跨 bean 调用，代理生效）；
 * 付款网关本波次为挡板（instructionRef 派生），真实银行通道归后续波次。</p>
 */
@Service
public class DisbursementApplicationService {

    private final DisbursementTxExecutor txExecutor;
    private final MyBatisIdempotencyStore idempotencyStore;
    private final DisbursementGateChecker gateChecker;
    private final DisbursementTxExecutor.ObjectMapperHolder mapperHolder;
    private final Clock clock;

    public DisbursementApplicationService(DisbursementTxExecutor txExecutor,
                                          MyBatisIdempotencyStore idempotencyStore,
                                          DisbursementGateChecker gateChecker,
                                          DisbursementTxExecutor.ObjectMapperHolder mapperHolder) {
        this.txExecutor = txExecutor;
        this.idempotencyStore = idempotencyStore;
        this.gateChecker = gateChecker;
        this.mapperHolder = mapperHolder;
        this.clock = Clock.systemUTC();
    }

    /**
     * POST /api/disbursements：执行代付。
     *
     * @param idempotencyKey Idempotency-Key（全局唯一作用域，R09 §6：代付全局唯一）
     */
    public DisbursementView execute(DisbursementRequest request, String idempotencyKey, String traceId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "Idempotency-Key 必填（R09 §2）");
        }
        String fingerprint = IdempotencyGuard.sha256Fingerprint(canonical(request));

        // ① 幂等守卫：同键同指纹 → 重放（返回首次结果）；同键异指纹 → RF_CONFLICT_001
        Optional<IdempotencyRecord> existing =
                IdempotencyGuard.checkOrThrow(idempotencyStore, idempotencyKey, fingerprint,
                        RFErrorCode.RF_CONFLICT_001);
        if (existing.isPresent()) {
            String replay = idempotencyStore.findResponse(idempotencyKey)
                    .orElseThrow(() -> new BusinessException(RFErrorCode.RF_CONFLICT_001,
                            "幂等键已存在但首次执行未完成，请稍后重试"));
            return readReplay(replay);
        }

        // ② G8 门禁（fail-closed，事务外同步调用；查不到/超时/拒绝 → RF_LOAN_001）
        gateChecker.checkAll(request.contractId(), request.underwritingCaseId());

        // ③ 本地事务：代付指令 + 债权状态迁移 + outbox 事件
        DisbursementView view = txExecutor.disburse(request, idempotencyKey, traceId);

        // ④ 幂等记录（首次成功结果；uk_disb_idempotency 唯一约束兜底并发）
        idempotencyStore.save(new IdempotencyRecord(idempotencyKey, fingerprint, Instant.now(clock)));
        idempotencyStore.saveResponse(idempotencyKey, mapperHolder.writeJson(view));
        return view;
    }

    /** 请求指纹（规范化内容：字段全列、顺序固定；不含时间戳等易变字段）。 */
    private String canonical(DisbursementRequest request) {
        return "loanId=%d|contractId=%d|underwritingCaseId=%d|payeeAccountRef=%s".formatted(
                request.loanId(), request.contractId(), request.underwritingCaseId(),
                request.payeeAccountRef() == null ? "" : request.payeeAccountRef());
    }

    private DisbursementView readReplay(String json) {
        try {
            return mapperHolder.getMapper().readValue(json, DisbursementView.class);
        } catch (Exception e) {
            throw new IllegalStateException("重放结果反序列化失败", e);
        }
    }

    public record DisbursementRequest(long loanId, long contractId, long underwritingCaseId,
                                      String payeeAccountRef) {
    }

    public record DisbursementView(long disbursementId, long loanId, long contractId, String status,
                                   long amount, String currency, String instructionRef,
                                   Instant paidAt, String loanStatus) {
    }
}
