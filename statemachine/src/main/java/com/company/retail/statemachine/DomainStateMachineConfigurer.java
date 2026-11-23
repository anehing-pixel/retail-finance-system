package com.company.retail.statemachine;

import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 领域状态图 → Spring StateMachine 引擎的装配基类（引擎底座）。
 *
 * <p><b>分层口径（架构侧裁决）</b>：领域是状态图的<b>唯一事实源</b>。
 * 引擎的迁移边一律由 {@link #domainGraph()}（领域枚举里的迁移表）生成，
 * 不在引擎侧另写一份状态图——因此不存在「两份图漂移」的可能。</p>
 *
 * <p><b>职责切分</b>：</p>
 * <ul>
 *   <li>领域（{@code *.domain}）：状态图 + 不变式 + 变更入口，<b>零 Spring</b>，纯 JUnit 可跑；</li>
 *   <li>引擎（本类）：事件分发、非法事件 fail-closed 拒绝、守卫执行、动作编排；</li>
 *   <li>领域仍是合法性的最终裁决者：动作调用领域方法，领域方法内部再断言一次迁移合法性。</li>
 * </ul>
 *
 * <p><b>未接线边</b>：领域图里存在、但领域尚未提供变更入口的边（无用例或属创建动作），
 * 必须在 {@link #unsupportedEdges()} 显式登记并说明理由；既不登记也不接事件，
 * 会在启动装配时 fail-fast（{@link IllegalStateException}），杜绝「图与引擎悄悄不一致」。</p>
 *
 * @param <S> 状态类型（枚举）
 * @param <E> 事件类型（枚举）
 */
public abstract class DomainStateMachineConfigurer<S extends Enum<S>, E extends Enum<E>>
        extends EnumStateMachineConfigurerAdapter<S, E> {

    /** 领域状态图（唯一事实源）：状态 → 允许迁移到的后继状态集合。 */
    protected abstract Map<S, Set<S>> domainGraph();

    /** 状态类型（枚举类）。 */
    protected abstract Class<S> stateType();

    /** 引擎初始态（实例真实状态一律由持久化状态 hydrate 覆盖，本值仅兜底）。 */
    protected abstract S initialState();

    /** 边 → 触发事件；返回 {@code null} 表示该领域边未接线（须在 {@link #unsupportedEdges()} 登记）。 */
    protected abstract E eventFor(S source, S target);

    /** 未接线的领域边（格式 {@code SOURCE->TARGET}）；默认无。 */
    protected Set<String> unsupportedEdges() {
        return Set.of();
    }

    /** 业务守卫：{@code true} 放行、{@code false} 拒绝。默认放行（合法性由领域图保证）。 */
    protected boolean allowed(StateContext<S, E> context) {
        return true;
    }

    /** 迁移动作：执行领域变更（领域方法自身仍是合法性的最终裁决者）。默认无动作。 */
    protected void apply(StateContext<S, E> context) {
        // 默认无动作（纯校验型状态机）
    }

    @Override
    public void configure(StateMachineStateConfigurer<S, E> states) throws Exception {
        states.withStates().initial(initialState()).states(EnumSet.allOf(stateType()));
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<S, E> transitions) throws Exception {
        Map<S, Set<S>> graph = domainGraph();
        for (Map.Entry<S, Set<S>> entry : graph.entrySet()) {
            for (S target : entry.getValue()) {
                E event = eventFor(entry.getKey(), target);
                if (event == null) {
                    if (!unsupportedEdges().contains(edgeKey(entry.getKey(), target))) {
                        throw new IllegalStateException("领域状态边缺少事件映射且未登记为未接线："
                                + edgeKey(entry.getKey(), target));
                    }
                    continue;
                }
                transitions.withExternal()
                        .source(entry.getKey())
                        .target(target)
                        .event(event)
                        .guard(context -> allowed(context))
                        .action(context -> applyAndRecord(context));
            }
        }
    }

    /**
     * 迁移动作包装：领域方法抛出的异常先记入 {@link TransitionFailure}，
     * 由执行器原样重抛（错误码与消息不因接入引擎而改变）。
     */
    private void applyAndRecord(StateContext<S, E> context) {
        try {
            apply(context);
        } catch (RuntimeException e) {
            Object holder = context.getMessageHeader(TransitionFailure.HEADER);
            if (holder instanceof TransitionFailure failure) {
                failure.fail(e);
            }
            throw e;
        }
    }

    /** 边的稳定标识（用于未接线登记）。 */
    protected static <T extends Enum<T>> String edgeKey(T source, T target) {
        return source.name() + "->" + target.name();
    }
}
