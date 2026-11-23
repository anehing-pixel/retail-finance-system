package com.company.retail.core.application.statemachine;

import com.company.retail.core.domain.Loan;
import com.company.retail.core.domain.LoanStatus;
import com.company.retail.statemachine.StateMachineRunner;
import com.company.retail.statemachine.TransitionOutcome;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 债权状态迁移入口（用例层唯一通道）。
 *
 * <p>用例层不再直接调用领域状态方法，而是投递事件给引擎：
 * 引擎判定迁移是否成立 → 成立则由动作调用领域方法完成变更 → 用例继续落库与发事件。
 * 事务边界不变（入金核销 + 余额 + 债权终态仍在同一本地事务，B-11）。</p>
 */
@Component
public class LoanTransitionService {

    private final StateMachineFactory<LoanStatus, LoanEvent> factory;

    public LoanTransitionService(StateMachineFactory<LoanStatus, LoanEvent> factory) {
        this.factory = factory;
    }

    /** 发起代付：PENDING_DISBURSEMENT / DISBURSE_FAILED → DISBURSING。 */
    public void startDisbursement(Loan loan) {
        fire(loan, LoanEvent.DISBURSE_START, Map.of());
    }

    /** 付款回调：DISBURSING → ACTIVE。 */
    public void completeDisbursement(Loan loan, Instant paidAt) {
        fire(loan, LoanEvent.PAYMENT_CONFIRMED, headers(LoanStateMachineConfigurer.HEADER_PAID_AT, paidAt));
    }

    /** 代付失败：DISBURSING → DISBURSE_FAILED。 */
    public void markDisburseFailed(Loan loan) {
        fire(loan, LoanEvent.DISBURSE_FAILED, Map.of());
    }

    /** 结清：ACTIVE / DELINQUENT → SETTLED（early=false）或 EARLY_SETTLED（early=true）。 */
    public void settle(Loan loan, boolean early, Instant settledAt) {
        fire(loan, early ? LoanEvent.EARLY_SETTLE : LoanEvent.SETTLE,
                headers(LoanStateMachineConfigurer.HEADER_SETTLED_AT, settledAt));
    }

    /** 逾期补救：DELINQUENT → ACTIVE。 */
    public void cure(Loan loan) {
        fire(loan, LoanEvent.CURE, Map.of());
    }

    /** 取消：PENDING_DISBURSEMENT / DISBURSE_FAILED → CANCELLED。 */
    public void cancel(Loan loan) {
        fire(loan, LoanEvent.CANCEL, Map.of());
    }

    private void fire(Loan loan, LoanEvent event, Map<String, Object> headers) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put(LoanStateMachineConfigurer.HEADER_LOAN, loan);
        all.putAll(headers);
        TransitionOutcome<LoanStatus> outcome = StateMachineRunner.fire(factory, loan.status(), event, all);
        if (!outcome.accepted()) {
            // 引擎拒绝 ⇒ 领域不允许该迁移：交回领域方法复现既有异常语义（错误码与消息一字不改）
            LoanStateMachineConfigurer.applyLoan(loan, event, all);
            throw new IllegalStateException("债权状态机拒绝迁移：" + loan.status() + " → " + event);
        }
    }

    private static Map<String, Object> headers(String key, Object value) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(key, value);
        return headers;
    }
}
