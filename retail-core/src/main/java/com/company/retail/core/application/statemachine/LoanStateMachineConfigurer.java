package com.company.retail.core.application.statemachine;

import com.company.retail.core.domain.Loan;
import com.company.retail.core.domain.LoanStatus;
import com.company.retail.statemachine.DomainStateMachineConfigurer;
import org.springframework.statemachine.StateContext;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 债权状态机引擎配置（R04 §3.4）。
 *
 * <p><b>分层口径</b>：状态图取自 {@link LoanStatus#graph()}（领域唯一事实源）；
 * 引擎只负责事件分发、非法事件 fail-closed 拒绝、守卫二次核对与动作编排。
 * 领域仍是合法性的<b>最终裁决者</b>——动作调用领域方法，领域方法内部再断言一次迁移合法性。</p>
 *
 * <p><b>未接线边</b>：逾期标记与坏账核销在领域层没有变更入口（无用例），
 * 按「不猜业务口径」原则显式登记为未接线，而不是臆造用例或静默吞掉。</p>
 */
public class LoanStateMachineConfigurer extends DomainStateMachineConfigurer<LoanStatus, LoanEvent> {

    /** 事件头：债权聚合（进程内传递，不落库、不出网）。 */
    public static final String HEADER_LOAN = "loan";
    /** 事件头：代付完成时点。 */
    public static final String HEADER_PAID_AT = "paidAt";
    /** 事件头：结清时点。 */
    public static final String HEADER_SETTLED_AT = "settledAt";

    @Override
    protected Map<LoanStatus, Set<LoanStatus>> domainGraph() {
        return LoanStatus.graph();
    }

    @Override
    protected Class<LoanStatus> stateType() {
        return LoanStatus.class;
    }

    @Override
    protected LoanStatus initialState() {
        return LoanStatus.PENDING_DISBURSEMENT;
    }

    @Override
    protected LoanEvent eventFor(LoanStatus source, LoanStatus target) {
        return switch (target) {
            case DISBURSING -> LoanEvent.DISBURSE_START;
            // 同一个目标态有两个来源事件：代付回调（D→ACTIVE）与逾期补救（DELINQUENT→ACTIVE）
            case ACTIVE -> source == LoanStatus.DELINQUENT ? LoanEvent.CURE : LoanEvent.PAYMENT_CONFIRMED;
            case DISBURSE_FAILED -> LoanEvent.DISBURSE_FAILED;
            case CANCELLED -> LoanEvent.CANCEL;
            case SETTLED -> LoanEvent.SETTLE;
            case EARLY_SETTLED -> LoanEvent.EARLY_SETTLE;
            // 领域尚未提供变更入口的边（见 unsupportedEdges）；
            // PENDING_DISBURSEMENT 是起始态，领域图里没有指向它的边
            case DELINQUENT, WRITTEN_OFF, PENDING_DISBURSEMENT -> null;
        };
    }

    @Override
    protected Set<String> unsupportedEdges() {
        return Set.of(
                "ACTIVE->DELINQUENT",        // 领域无「标记逾期」变更入口
                "ACTIVE->WRITTEN_OFF",       // 领域无「坏账核销」变更入口
                "DELINQUENT->WRITTEN_OFF");  // 同上
    }

    @Override
    protected boolean allowed(StateContext<LoanStatus, LoanEvent> context) {
        Loan loan = loanOf(context);
        if (loan == null || context.getTarget() == null) {
            return false;
        }
        // 领域合法性二次核对（迁移边本就来自领域图，这里对聚合实时状态再核一次）
        return loan.status().canTransitionTo(context.getTarget().getId());
    }

    @Override
    protected void apply(StateContext<LoanStatus, LoanEvent> context) {
        Loan loan = loanOf(context);
        if (loan == null) {
            throw new IllegalStateException("债权状态机事件缺少聚合上下文");
        }
        applyLoan(loan, context.getEvent(), context.getMessageHeaders());
    }

    /**
     * 事件 → 领域变更入口（引擎动作与用例层共用同一份派发，杜绝两处漂移）。
     */
    public static void applyLoan(Loan loan, LoanEvent event, Map<String, Object> headers) {
        switch (event) {
            case DISBURSE_START -> loan.startDisbursement();
            case PAYMENT_CONFIRMED -> loan.completeDisbursement((Instant) headers.get(HEADER_PAID_AT));
            case DISBURSE_FAILED -> loan.markDisburseFailed();
            case CANCEL -> loan.cancel();
            case SETTLE -> loan.settle(false, (Instant) headers.get(HEADER_SETTLED_AT));
            case EARLY_SETTLE -> loan.settle(true, (Instant) headers.get(HEADER_SETTLED_AT));
            case CURE -> loan.cureFromDelinquent();
        }
    }

    private static Loan loanOf(StateContext<LoanStatus, LoanEvent> context) {
        Object value = context.getMessageHeader(HEADER_LOAN);
        return value instanceof Loan loan ? loan : null;
    }
}
