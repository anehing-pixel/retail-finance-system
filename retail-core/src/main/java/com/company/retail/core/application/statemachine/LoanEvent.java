package com.company.retail.core.application.statemachine;

/**
 * 债权状态机事件（引擎触发词汇）。
 *
 * <p><b>不是对外事件名</b>：对外领域事件（{@code DISBURSEMENT_PAID} / {@code LOAN_SETTLED} 等）
 * 见 {@code R09 §5}，由 outbox 发布；本枚举只存在于进程内，用于驱动状态机迁移。</p>
 */
public enum LoanEvent {

    /** 发起代付（PENDING_DISBURSEMENT / DISBURSE_FAILED → DISBURSING）。 */
    DISBURSE_START,
    /** 付款回调确认（DISBURSING → ACTIVE）。 */
    PAYMENT_CONFIRMED,
    /** 代付失败（DISBURSING → DISBURSE_FAILED）。 */
    DISBURSE_FAILED,
    /** 取消（→ CANCELLED）。 */
    CANCEL,
    /** 全部结清（→ SETTLED）。 */
    SETTLE,
    /** 提前结清（→ EARLY_SETTLED）。 */
    EARLY_SETTLE,
    /** 逾期补救（DELINQUENT → ACTIVE）。 */
    CURE
}
