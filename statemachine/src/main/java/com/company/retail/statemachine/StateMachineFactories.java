package com.company.retail.statemachine;

import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.config.builders.StateMachineConfigBuilder;

/**
 * 状态机工厂构建助手（生产装配与单测共用一份构建逻辑）。
 *
 * <p>构建过程会把配置器里的领域状态图一次性物化成迁移边：
 * 领域边缺事件映射且未登记为未接线时，<b>构建期即失败</b>（fail-fast），
 * 不会等到线上第一次迁移才暴露。</p>
 */
public final class StateMachineFactories {

    private StateMachineFactories() {
    }

    /**
     * 按配置器构建状态机工厂。
     *
     * @param configurer 状态机配置器（领域图 → 迁移边 + 守卫 + 动作）
     * @param <S>       状态类型
     * @param <E>       事件类型
     */
    public static <S extends Enum<S>, E extends Enum<E>> StateMachineFactory<S, E> create(
            DomainStateMachineConfigurer<S, E> configurer) throws Exception {
        StateMachineConfigBuilder<S, E> builder = new StateMachineConfigBuilder<>();
        configurer.init(builder);
        return StateMachineFactory.create(builder);
    }
}
