package com.company.retail.statemachine;

import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.StateMachineEventResult;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 状态机执行器（每次业务操作一台机器，用完即弃）。
 *
 * <p><b>为什么每次新建</b>：本项目的状态权威在数据库（聚合状态 + 乐观锁），
 * 引擎不做状态持久化；每次操作按库中状态 hydrate 一台机器、发一个事件、取结果即走。
 * 这样避免「内存态与库态不一致」——内存状态机不是真相源，只是执行器。</p>
 *
 * <p><b>如何判定迁移成立</b>：<b>不</b>用事件结果类型（引擎在守卫拒绝时同样返回
 * {@code ACCEPTED}——事件被消费不等于迁移发生），而是<b>比对迁移前后的状态</b>：
 * 状态变了才算迁移成立，没变即视为拒绝（fail-closed，调用方必须抛异常，不得静默放行）。</p>
 */
public final class StateMachineRunner {

    /** 单次事件处理上限（引擎为进程内同步执行，正常在毫秒级完成）。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private StateMachineRunner() {
    }

    /**
     * 在 {@code current} 状态下投递事件 {@code event}。
     *
     * @param factory  状态机工厂（启动期装配，领域图生成迁移边）
     * @param current  当前状态（来自持久化状态）
     * @param event    触发事件
     * @param headers  事件头（承载聚合实例与业务参数，进程内传递，不落库、不出网）
     * @return 迁移结果（接受则状态已变更；拒绝则状态未变更）
     */
    public static <S extends Enum<S>, E extends Enum<E>> TransitionOutcome<S> fire(
            StateMachineFactory<S, E> factory, S current, E event, Map<String, Object> headers) {
        TransitionFailure failure = new TransitionFailure();
        Map<String, Object> allHeaders = new LinkedHashMap<>();
        if (headers != null) {
            allHeaders.putAll(headers);
        }
        allHeaders.put(TransitionFailure.HEADER, failure);

        StateMachine<S, E> machine = factory.getStateMachine();
        try {
            machine.stopReactively().block();
            machine.getStateMachineAccessor().doWithAllRegions(access -> access
                    .resetStateMachineReactively(new DefaultStateMachineContext<S, E>(current, null, null, null))
                    .block());
            machine.startReactively().block();

            Message<E> message = MessageBuilder.withPayload(event).copyHeaders(allHeaders).build();
            List<StateMachineEventResult<S, E>> results;
            try {
                results = machine.sendEvent(Mono.just(message)).collectList().block(TIMEOUT);
            } catch (RuntimeException e) {
                // 动作抛出的领域异常必须原样回到调用方（错误码/消息一个字都不改）
                throw failure.hasFailure() ? failure.failure() : e;
            }
            if (failure.hasFailure()) {
                throw failure.failure();
            }
            S after = machine.getState() == null ? current : machine.getState().getId();
            boolean moved = !Objects.equals(current, after);
            return moved
                    ? TransitionOutcome.accepted(after)
                    : TransitionOutcome.rejected(after,
                            "状态机未发生迁移：事件 " + event + "（当前状态 " + current + "）");
        } finally {
            machine.stopReactively().block();
        }
    }
}
