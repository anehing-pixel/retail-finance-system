package com.company.retail.titlerelease.application.statemachine;

import com.company.retail.statemachine.StateMachineFactories;
import com.company.retail.titlerelease.domain.ReleaseOrderStatus;
import com.company.retail.titlerelease.domain.TitleRetentionStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.config.builders.StateMachineConfigBuilder;

/**
 * 解除链路两台状态机的引擎装配（启动期 fail-fast）。
 *
 * <p>两台机器分开的原因就是<b>两态分离</b>：解除订单与所有权保留是不同聚合、
 * 不同状态图，混成一台机器会让「出具文件」有机会顺手改掉保留状态。</p>
 */
@Configuration
public class ReleaseStateMachineConfig {

    /** 解除订单状态机工厂（迁移边由领域图生成）。 */
    @Bean
    public StateMachineFactory<ReleaseOrderStatus, ReleaseOrderEvent> releaseOrderStateMachineFactory()
            throws Exception {
        return StateMachineFactories.create(new ReleaseOrderStateMachineConfigurer());
    }

    /** 所有权保留状态机工厂（迁移边由领域图生成）。 */
    @Bean
    public StateMachineFactory<TitleRetentionStatus, TitleRetentionEvent> titleRetentionStateMachineFactory()
            throws Exception {
        return StateMachineFactories.create(new TitleRetentionStateMachineConfigurer());
    }
}
