package com.company.retail.titlerelease.config;

import com.company.platform.event.EventConsumer;
import com.company.retail.infrastructure.messaging.MqEventRelayConsumer;
import com.company.retail.infrastructure.outbox.EventConsumptionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * title-release-service 的 MQ 装配（ADR-RF-014 §4）。
 *
 * <p>通用中继消费端：把 `RF_EVENT_RELAY` 主题上的事件信封解密后交给本服务的
 * {@link EventConsumer} 执行（与直投共用同一张幂等表）。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "retail.mq", name = "relay-consumer-enabled", havingValue = "true")
public class TitleReleaseMqConfig {

    @Bean
    public MqEventRelayConsumer titleReleaseRelayConsumer(EventConsumptionService consumption,
                                                            ObjectProvider<EventConsumer> consumers) {
        return new MqEventRelayConsumer(consumption, consumers.orderedStream().toList());
    }
}
