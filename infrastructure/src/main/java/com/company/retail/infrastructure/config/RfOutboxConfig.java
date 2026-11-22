package com.company.retail.infrastructure.config;

import com.company.platform.event.EventConsumer;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.infrastructure.outbox.RfEventDispatcher;
import com.company.retail.infrastructure.outbox.RfJdbcOutboxPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * outbox 机制装配（各业务服务 {@code @Import}）：发布端 + 消费调度端。
 *
 * <p>服务没有消费者时 {@link RfEventDispatcher#poll} 直接空转返回，不发一条 SQL；
 * 消费者随 S2 起按 R04 §6 的事件生产消费关系逐个落进各自服务。</p>
 */
@Configuration
@EnableScheduling
public class RfOutboxConfig {

    @Bean
    public OutboxPublisher outboxPublisher(JdbcTemplate jdbcTemplate) {
        return new RfJdbcOutboxPublisher(jdbcTemplate);
    }

    @Bean
    public RfEventDispatcher eventDispatcher(JdbcTemplate jdbcTemplate,
                                             ObjectProvider<EventConsumer> consumerProvider) {
        return new RfEventDispatcher(jdbcTemplate, consumerProvider);
    }
}
