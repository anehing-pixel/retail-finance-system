package com.company.retail.statemachine;

import org.junit.jupiter.api.Test;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.config.builders.StateMachineConfigBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎底座自测（statemachine 共享模块）。
 *
 * <p>覆盖：① 合法迁移被接受且动作执行；② 非法事件被拒绝（fail-closed）；
 * ③ 守卫未通过被拒绝；④ 领域边缺事件映射且未登记 → 启动装配 fail-fast。</p>
 */
class DomainStateMachineConfigurerTest {

    /** 玩具领域状态机（模拟「A → B → C」的领域迁移表）。 */
    enum S { A, B, C }

    enum E { TO_B, TO_C }

    static final Map<S, Set<S>> GRAPH = Map.of(
            S.A, Set.of(S.B),
            S.B, Set.of(S.C),
            S.C, Set.of());

    static class ToyConfigurer extends DomainStateMachineConfigurer<S, E> {
        boolean guardPass = true;
        final List<String> applied = new ArrayList<>();

        @Override
        protected Map<S, Set<S>> domainGraph() {
            return GRAPH;
        }

        @Override
        protected Class<S> stateType() {
            return S.class;
        }

        @Override
        protected S initialState() {
            return S.A;
        }

        @Override
        protected E eventFor(S source, S target) {
            return target == S.B ? E.TO_B : E.TO_C;
        }

        @Override
        protected boolean allowed(StateContext<S, E> context) {
            return guardPass;
        }

        @Override
        protected void apply(StateContext<S, E> context) {
            applied.add(context.getEvent().name());
        }
    }

    private StateMachineFactory<S, E> factory(ToyConfigurer configurer) throws Exception {
        StateMachineConfigBuilder<S, E> builder = new StateMachineConfigBuilder<>();
        configurer.init(builder);
        return StateMachineFactory.create(builder);
    }

    @Test
    void legalTransition_isAcceptedAndActionRuns() throws Exception {
        ToyConfigurer configurer = new ToyConfigurer();
        TransitionOutcome<S> outcome = StateMachineRunner.fire(
                factory(configurer), S.A, E.TO_B, Map.of());
        assertTrue(outcome.accepted(), "合法迁移应被接受");
        assertEquals(S.B, outcome.state(), "迁移后状态应为 B");
        assertEquals(List.of("TO_B"), configurer.applied, "动作应被执行且仅执行一次");
    }

    @Test
    void illegalEvent_isDeniedWithoutAction() throws Exception {
        ToyConfigurer configurer = new ToyConfigurer();
        // A 没有 → C 的边
        TransitionOutcome<S> outcome = StateMachineRunner.fire(
                factory(configurer), S.A, E.TO_C, Map.of());
        assertFalse(outcome.accepted(), "无迁移边的事件必须被拒绝");
        assertEquals(S.A, outcome.state(), "被拒绝时状态不得变化");
        assertTrue(configurer.applied.isEmpty(), "被拒绝时动作不得执行");
    }

    @Test
    void guardRejection_isDeniedWithoutAction() throws Exception {
        ToyConfigurer configurer = new ToyConfigurer();
        configurer.guardPass = false;
        TransitionOutcome<S> outcome = StateMachineRunner.fire(
                factory(configurer), S.A, E.TO_B, Map.of());
        assertFalse(outcome.accepted(), "守卫未通过必须被拒绝");
        assertEquals(S.A, outcome.state(), "被拒绝时状态不得变化");
        assertTrue(configurer.applied.isEmpty(), "被拒绝时动作不得执行");
    }

    @Test
    void missingEventMapping_failsFastAtAssembly() {
        ToyConfigurer broken = new ToyConfigurer() {
            @Override
            protected E eventFor(S source, S target) {
                return target == S.B ? E.TO_B : null;   // B → C 未接事件且未登记
            }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> factory(broken));
        assertTrue(e.getMessage().contains("B->C"), "报错应指出缺失的领域边");
    }

    @Test
    void unsupportedEdgeDeclared_isSkippedSilently() throws Exception {
        ToyConfigurer partial = new ToyConfigurer() {
            @Override
            protected E eventFor(S source, S target) {
                return target == S.B ? E.TO_B : null;
            }

            @Override
            protected Set<String> unsupportedEdges() {
                return Set.of("B->C");
            }
        };
        StateMachineFactory<S, E> factory = factory(partial);
        assertTrue(factory.getStateMachine() != null, "登记为未接线的边应可正常装配");
    }
}
