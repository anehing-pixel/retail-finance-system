package com.company.retail.statemachine;

/**
 * 状态迁移结果（引擎裁决结果）。
 *
 * <p>{@code accepted=true} 表示引擎接受了事件并已执行动作（领域变更已发生）；
 * {@code accepted=false} 表示引擎拒绝（没有可用迁移，或守卫未通过）——
 * 拒绝即 fail-closed，调用方必须按业务口径抛出异常，不得静默放行。</p>
 *
 * @param <S> 状态类型
 */
public record TransitionOutcome<S>(boolean accepted, S state, String reason) {

    /** 迁移被接受。 */
    public static <S> TransitionOutcome<S> accepted(S state) {
        return new TransitionOutcome<>(true, state, null);
    }

    /** 迁移被拒绝（附拒绝原因）。 */
    public static <S> TransitionOutcome<S> rejected(S state, String reason) {
        return new TransitionOutcome<>(false, state, reason);
    }
}
