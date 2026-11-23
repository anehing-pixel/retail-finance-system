package com.company.retail.infrastructure.config;

import com.company.platform.event.EventConsumer;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.infrastructure.mapper.OutboxMapper;
import com.company.retail.infrastructure.outbox.EventConsumptionService;
import com.company.retail.infrastructure.outbox.MyBatisOutboxPublisher;
import com.company.retail.infrastructure.outbox.RfEventDispatcher;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * outbox 机制装配（各业务服务 {@code @Import}）：发布端 + 消费调度端。
 *
 * <p>服务没有消费者时 {@link RfEventDispatcher#poll} 直接空转返回，不发一条 SQL；
 * 消费者随 S2 起按 R04 §6 的事件生产消费关系逐个落进各自服务。</p>
 *
 * <p>持久层：共享基础设施的 Mapper 统一在 {@code com.company.retail.infrastructure.mapper}
 * 包，由本配置显式注册（精确装配纪律：不使用组件全包扫描，也不依赖 starter 自动扫描）。</p>
 */
@Configuration
@EnableScheduling
@MapperScan("com.company.retail.infrastructure.mapper")
public class RfOutboxConfig {

    @Bean
    public OutboxPublisher outboxPublisher(OutboxMapper outboxMapper) {
        return new MyBatisOutboxPublisher(outboxMapper);
    }

    /** 消费记账与执行（直投与 MQ 中继共用，ADR-RF-014）。 */
    @Bean
    public EventConsumptionService eventConsumptionService(OutboxMapper outboxMapper) {
        return new EventConsumptionService(outboxMapper);
    }

    @Bean
    public RfEventDispatcher eventDispatcher(OutboxMapper outboxMapper,
                                             ObjectProvider<EventConsumer> consumerProvider,
                                             EventConsumptionService eventConsumptionService,
                                             ObjectProvider<MqProperties> mqPropertiesProvider) {
        return new RfEventDispatcher(outboxMapper, consumerProvider, eventConsumptionService,
                mqPropertiesProvider);
    }
}
