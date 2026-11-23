package com.company.retail.infrastructure.messaging;

import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopePayloadGuard;
import com.company.retail.common.messaging.EnvelopePublisher;
import com.company.retail.common.messaging.MqEnvelopeException;
import com.company.retail.infrastructure.config.MqProperties;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.nio.charset.StandardCharsets;

/**
 * 密文信封发布端（RocketMQ 生产者，ADR-RF-014）。
 *
 * <p><b>只用 rocketmq-client，不引 rocketmq-spring-boot-starter</b>：后者在 Spring Boot 3.2
 * 组合下的自动装配条件与本仓「精确装配」纪律不咬合；直接持有 {@link DefaultMQProducer}
 * 由本类管生命周期（启动 / 停止），依赖面最小、行为可预期。</p>
 *
 * <p><b>发布端守卫</b>：投递前过 {@link EnvelopePayloadGuard}（顶层键白名单 + 明文 PII 键拒绝）。
 * 守卫不过就不投——broker 落盘的密文行永久保留，事后撤回不可能，预防是唯一手段。</p>
 *
 * <p><b>失败语义</b>：投递失败抛 {@link MqEnvelopeException}，由调用方决定回滚（同步路径）
 * 还是标记待补（异步路径）。绝不静默吞掉——丢了密文信封 = 丢了业务动作。</p>
 */
public class RocketMqEnvelopePublisher implements EnvelopePublisher, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RocketMqEnvelopePublisher.class);

    private final MqProperties props;
    private final DefaultMQProducer producer;

    public RocketMqEnvelopePublisher(MqProperties props) {
        this.props = props;
        this.producer = new DefaultMQProducer(props.getProducerGroup());
        // 生产者与消费者必须使用不同 instanceName：同 JVM 内 clientId 相同会互相顶掉
        this.producer.setInstanceName("rf-producer-" + props.getProducerGroup());
        this.producer.setNamesrvAddr(props.getNameServer());
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        producer.start();
        log.info("RocketMQ 生产者已启动 group={} namesrv={}", props.getProducerGroup(), props.getNameServer());
    }

    @Override
    public void destroy() {
        producer.shutdown();
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void publish(String topic, CipherEnvelope envelope) {
        if (envelope == null) {
            throw new MqEnvelopeException("密文信封为空，拒绝投递");
        }
        String json = envelope.toJson();
        EnvelopePayloadGuard.validate(json);
        Message message = new Message(topic, json.getBytes(StandardCharsets.UTF_8));
        message.setKeys(envelope.bizRef());
        try {
            SendResult result = producer.send(message, props.getSendTimeoutMs());
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new MqEnvelopeException("密文信封投递未成功 topic=" + topic
                        + " envelopeId=" + envelope.envelopeId()
                        + " status=" + (result == null ? "null" : result.getSendStatus()));
            }
            log.info("密文信封已投递 topic={} envelopeId={} bizType={} bizRef={}",
                    topic, envelope.envelopeId(), envelope.bizType(), envelope.bizRef());
        } catch (MqEnvelopeException e) {
            throw e;
        } catch (Exception e) {
            throw new MqEnvelopeException("密文信封投递失败 topic=" + topic
                    + " envelopeId=" + envelope.envelopeId(), e);
        }
    }
}
