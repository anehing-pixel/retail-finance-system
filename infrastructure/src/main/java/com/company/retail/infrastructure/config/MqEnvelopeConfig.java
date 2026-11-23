package com.company.retail.infrastructure.config;

import com.company.retail.common.messaging.AesGcmEnvelopeCipher;
import com.company.retail.common.messaging.EnvelopeConsumer;
import com.company.retail.common.messaging.EnvelopeCipher;
import com.company.retail.common.messaging.EnvelopePublisher;
import com.company.retail.common.messaging.NoopEnvelopePublisher;
import com.company.retail.infrastructure.mapper.MqEnvelopeInboxMapper;
import com.company.retail.infrastructure.mapper.OutboxMapper;
import com.company.retail.infrastructure.messaging.MqEnvelopeDispatcher;
import com.company.retail.infrastructure.messaging.MqOutboxRelay;
import com.company.retail.infrastructure.messaging.RocketMqEnvelopePublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * MQ 通道装配（ADR-RF-014）。
 *
 * <p><b>精确装配</b>：本类由各服务按需 {@code @Import}——不需要 MQ 的服务不背这个依赖，
 * 也不注册任何监听器（与全仓「不做全包扫描」的纪律一致）。</p>
 *
 * <p><b>默认关闭</b>：{@code retail.mq.enabled=false}（缺省）时装配的是
 * {@link NoopEnvelopePublisher}——{@code enabled()} 恒为 false，调用方据此走同步路径。
 * 开关打开后才装配生产端、消费端与密文信封密钥。</p>
 *
 * <p><b>密钥回落</b>：信封数据密钥为空时回落 {@code retail.pii.data-key}——
 * 密文信封承载的就是 PII，不新增密钥面（R06 §5）。两者都空则启动失败（fail-closed）：
 * 没有密钥就不许开 MQ 通道。</p>
 */
@Configuration
@EnableConfigurationProperties(MqProperties.class)
public class MqEnvelopeConfig {

    /** 未启用 MQ：发布端为空实现（enabled()=false → 调用方走同步路径）。 */
    @Bean
    @ConditionalOnProperty(prefix = "retail.mq", name = "enabled", havingValue = "false", matchIfMissing = true)
    public EnvelopePublisher noopEnvelopePublisher() {
        return new NoopEnvelopePublisher();
    }

    /** 启用 MQ：密文信封加解密器。 */
    @Bean
    @ConditionalOnProperty(prefix = "retail.mq", name = "enabled", havingValue = "true")
    public EnvelopeCipher envelopeCipher(MqProperties props, Environment environment) {
        String key = props.getDataKey();
        if (key == null || key.isBlank()) {
            key = environment.getProperty("retail.pii.data-key", "");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "MQ 通道已启用但信封数据密钥为空（retail.mq.data-key / retail.pii.data-key）——fail-closed 拒绝启动");
        }
        return new AesGcmEnvelopeCipher(key, props.getKeyVersion());
    }

    /** 启用 MQ：RocketMQ 生产端。 */
    @Bean
    @ConditionalOnProperty(prefix = "retail.mq", name = "enabled", havingValue = "true")
    public RocketMqEnvelopePublisher rocketMqEnvelopePublisher(MqProperties props) {
        return new RocketMqEnvelopePublisher(props);
    }

    /** 启用 MQ：消费端（按服务注册的 EnvelopeConsumer 逐主题订阅）。 */
    @Bean
    @ConditionalOnProperty(prefix = "retail.mq", name = "enabled", havingValue = "true")
    public MqEnvelopeDispatcher mqEnvelopeDispatcher(MqProperties props,
                                                     EnvelopeCipher cipher,
                                                     MqEnvelopeInboxMapper inbox,
                                                     ObjectProvider<EnvelopeConsumer> consumers) {
        return new MqEnvelopeDispatcher(props, cipher, inbox, consumers.orderedStream().toList());
    }

    /** 通用中继开关打开：outbox 事件 → MQ（仅 retail-core 承担，避免多实例重复中继）。 */
    @Bean
    @ConditionalOnProperty(prefix = "retail.mq", name = "relay-enabled", havingValue = "true")
    public MqOutboxRelay mqOutboxRelay(MqProperties props,
                                       OutboxMapper mapper,
                                       EnvelopeCipher cipher,
                                       EnvelopePublisher publisher) {
        return new MqOutboxRelay(props, mapper, cipher, publisher);
    }
}
