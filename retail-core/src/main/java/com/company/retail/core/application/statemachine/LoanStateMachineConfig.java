package com.company.retail.core.application.statemachine;

import com.company.retail.core.domain.LoanStatus;
import com.company.retail.statemachine.StateMachineFactories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.StateMachineFactory;

/**
 * 债权状态机引擎装配。
 *
 * <p>启动期 fail-fast：领域图与事件映射对不上（例如某条领域边既没接事件也没登记为未接线），
 * 会在本 Bean 创建时直接失败，而不是等到线上第一次迁移才暴露。</p>
 */
@Configuration
public class LoanStateMachineConfig {

    /** 债权状态机工厂（迁移边由领域图生成）。 */
    @Bean
    public StateMachineFactory<LoanStatus, LoanEvent> loanStateMachineFactory() throws Exception {
        return StateMachineFactories.create(new LoanStateMachineConfigurer());
    }
}
