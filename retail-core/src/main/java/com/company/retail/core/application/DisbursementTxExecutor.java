package com.company.retail.core.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.core.domain.Loan;
import com.company.retail.core.integration.BankPayAdapter;
import com.company.retail.core.persistence.JdbcDisbursementRepository;
import com.company.retail.core.persistence.JdbcLoanRepository;
import com.company.retail.domain.exception.RFErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 代付本地事务执行器（R05 S7–S8：付款指令与债权状态迁移同一本地事务）。
 *
 * <p>独立 bean 的原因：{@code @Transactional} 自调用不经过代理会失效——
 * 门禁/幂等在 {@link DisbursementApplicationService}（无事务），事务边界只包本地账务写。</p>
 */
@Component
public class DisbursementTxExecutor {

    private static final Logger log = LoggerFactory.getLogger(DisbursementTxExecutor.class);

    private final JdbcLoanRepository loanRepository;
    private final JdbcDisbursementRepository disbursementRepository;
    private final OutboxPublisher outbox;
    private final ObjectMapperHolder mapperHolder;
    /** 银行代付网关（W-SIM2 双模：挡板/HTTP，配置 retail.core.bank-pay-url）。 */
    private final BankPayAdapter bankPayAdapter;

    public DisbursementTxExecutor(JdbcLoanRepository loanRepository,
                                  JdbcDisbursementRepository disbursementRepository,
                                  OutboxPublisher outbox,
                                  ObjectMapperHolder mapperHolder,
                                  BankPayAdapter bankPayAdapter) {
        this.loanRepository = loanRepository;
        this.disbursementRepository = disbursementRepository;
        this.outbox = outbox;
        this.mapperHolder = mapperHolder;
        this.bankPayAdapter = bankPayAdapter;
    }

    @Transactional
    public DisbursementApplicationService.DisbursementView disburse(
            DisbursementApplicationService.DisbursementRequest request, String idempotencyKey, String traceId) {
        Loan loan = loanRepository.findByIdForUpdate(request.loanId())
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "债权不存在", Map.of("loanId", String.valueOf(request.loanId()))));
        if (loan.contractId() != request.contractId()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    "loanId 与 contractId 不匹配", Map.of(
                            "loanContractId", String.valueOf(loan.contractId()),
                            "requestContractId", String.valueOf(request.contractId())));
        }
        loan.assertDisbursementAllowed(); // INV-1（uk_disb_loan 之外的领域层守卫）

        loan.startDisbursement();
        java.time.Instant paidAt = java.time.Instant.now();
        String instructionRef = request.payeeAccountRef() == null || request.payeeAccountRef().isBlank()
                ? "INS-" + loan.loanId()
                : "INS-" + loan.loanId() + "-" + request.payeeAccountRef();
        // 银行网关外呼（W-SIM2：挡板=同步假成功；HTTP=真实对端）。失败→抛出回滚整个本地事务，
        // 不产生半代付状态（债权仍 DISBURSING 前态，重试幂等键可重发）
        BankPayAdapter.PayResult bank = bankPayAdapter.pay(instructionRef, loan.principal(), request.payeeAccountRef());
        if (!bank.paid()) {
            throw new BusinessException(RFErrorCode.RF_SYS_001, "银行代付未受理",
                    Map.of("instructionRef", instructionRef, "reason", bank.rawMessage()));
        }
        long disbursementId;
        try {
            disbursementId = disbursementRepository.insert(loan.loanId(), request.payeeAccountRef(),
                    loan.principal(), "PAID", instructionRef, java.sql.Timestamp.from(paidAt),
                    idempotencyKey, traceId);
        } catch (DuplicateKeyException e) {
            // uk_disb_loan / uk_disb_idempotency：三层幂等兜底（R09 §6）
            throw new BusinessException(RFErrorCode.RF_LOAN_002, "重复代付（唯一约束兜底）");
        }
        loan.completeDisbursement(paidAt);
        if (loanRepository.updateAfterDisbursement(loan) == 0) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001, "债权并发更新冲突（乐观锁 version）");
        }

        // DISBURSEMENT_PAID（R09 §5.1 聚合键 disbursementId；payload 过白名单守卫，只带业务主键）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("disbursementId", disbursementId);
        payload.put("loanId", loan.loanId());
        payload.put("contractId", loan.contractId());
        payload.put("amount", loan.principal());
        payload.put("currency", "JPY");
        payload.put("status", "PAID");
        payload.put("traceId", traceId);
        outbox.publish(OutboxPublisher.DISBURSEMENT_PAID, String.valueOf(disbursementId),
                mapperHolder.writeJson(payload));

        log.info("代付完成 loanId={} contractId={} disbursementId={} amount={} bankRef={}",
                loan.loanId(), loan.contractId(), disbursementId, loan.principal(), bank.bankRef());
        return new DisbursementApplicationService.DisbursementView(disbursementId, loan.loanId(),
                loan.contractId(), "PAID", loan.principal(), "JPY", instructionRef, paidAt,
                loan.status().name());
    }

    /** ObjectMapper 桥（避免本类直接依赖注入序列化器，收口一处）。 */
    @org.springframework.stereotype.Component
    public static class ObjectMapperHolder {
        private final com.fasterxml.jackson.databind.ObjectMapper mapper;

        public ObjectMapperHolder(com.fasterxml.jackson.databind.ObjectMapper mapper) {
            this.mapper = mapper;
        }

        public String writeJson(Object value) {
            try {
                return mapper.writeValueAsString(value);
            } catch (Exception e) {
                throw new IllegalStateException("JSON 序列化失败", e);
            }
        }

        public com.fasterxml.jackson.databind.ObjectMapper getMapper() {
            return mapper;
        }
    }
}
