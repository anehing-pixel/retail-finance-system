package com.company.retail.statemachine;

/**
 * 迁移动作异常收集器（进程内传递，不落库、不出网）。
 *
 * <p><b>为什么需要它</b>：引擎动作里调用的是领域方法，领域方法抛出的异常（如
 * {@code BusinessException(RF_VALID_002)}）必须<b>原样</b>回到调用方——错误码与消息
 * 一个字都不能变（对外接口语义不变是硬要求）。而引擎内部对动作异常的处理并不可控，
 * 因此动作侧先把异常记在这里，执行器在事件处理结束后原样重抛。</p>
 */
public final class TransitionFailure {

    /** 事件头键（执行器自动放入事件头，动作侧取出）。 */
    public static final String HEADER = "rf.transition.failure";

    private RuntimeException failure;

    /** 记录动作异常。 */
    public void fail(RuntimeException e) {
        this.failure = e;
    }

    /** 是否记录到异常。 */
    public boolean hasFailure() {
        return failure != null;
    }

    /** 取回记录的异常（未记录时返回 null）。 */
    public RuntimeException failure() {
        return failure;
    }
}
