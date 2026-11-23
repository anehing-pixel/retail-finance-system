package com.company.retail.infrastructure.messaging;

import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeConsumer;
import com.company.retail.common.messaging.EnvelopePayloadGuard;
import com.company.retail.common.messaging.MqEnvelopeException;
import com.company.retail.infrastructure.config.MqProperties;
import com.company.retail.infrastructure.mapper.MqEnvelopeInboxMapper;
import com.company.retail.common.persistence.RowValues;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 密文信封消费端（RocketMQ 推送消费者，ADR-RF-014 §3）。
 *
 * <p><b>三段职责</b>：① 消费端守卫（信封顶层键白名单 + 明文 PII 键拒绝）；
 * ② 幂等（{@code integration.mq_envelope_inbox}，主键 envelope_id + consumer）；
 * ③ 解密后交给业务 {@link EnvelopeConsumer}——本类不碰业务语义，只管机制。</p>
 *
 * <p><b>失败语义</b>：与直投 outbox 同口径——失败返回 {@code RECONSUME_LATER} 让 broker 重投，
 * 累计 3 次仍失败则置 DEAD 并停止重投（一条坏消息不得阻塞整条链路），
 * DEAD 记录留在 inbox 表供人工补发。</p>
 *
 * <p><b>削峰旋钮</b>：{@code consumeThreadMax} / {@code pullBatchSize} 决定消费侧速率，
 * 生产侧洪峰由 broker 吸收——这就是「控制器把密文扔进 MQ」的全部机制。</p>
 */
public class MqEnvelopeDispatcher implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MqEnvelopeDispatcher.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_ERROR_LEN = 500;

    private final MqProperties props;
    private final com.company.retail.common.messaging.EnvelopeCipher cipher;
    private final MqEnvelopeInboxMapper inbox;
    private final List<EnvelopeConsumer> consumers;
    private final List<DefaultMQPushConsumer> started = new ArrayList<>();

    public MqEnvelopeDispatcher(MqProperties props,
                                com.company.retail.common.messaging.EnvelopeCipher cipher,
                                MqEnvelopeInboxMapper inbox,
                                List<EnvelopeConsumer> consumers) {
        this.props = props;
        this.cipher = cipher;
        this.inbox = inbox;
        this.consumers = consumers == null ? List.of() : consumers;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        Map<String, EnvelopeConsumer> byTopic = new LinkedHashMap<>();
        for (EnvelopeConsumer c : consumers) {
            byTopic.put(c.topic(), c);
        }
        for (Map.Entry<String, EnvelopeConsumer> entry : byTopic.entrySet()) {
            EnvelopeConsumer target = entry.getValue();
            DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(props.getConsumerGroup());
            consumer.setNamesrvAddr(props.getNameServer());
            consumer.setInstanceName("rf-consumer-" + props.getConsumerGroup());
            consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            consumer.setConsumeThreadMax(props.getConsumeThreadMax());
            // RocketMQ 客户端校验：consumeThreadMax 必须 ≥ consumeThreadMin（默认 20）
            consumer.setConsumeThreadMin(Math.min(props.getConsumeThreadMax(), 20));
            consumer.setPullBatchSize(props.getPullBatchSize());
            consumer.subscribe(entry.getKey(), "*");
            consumer.registerMessageListener((MessageListenerConcurrently) (messages, ctx) -> {
                // RocketMQ 5.0 的并发监听回调整批只回一个状态：任一条失败即整批重投。
                // 已 DONE 的信封靠 inbox 幂等跳过，不会重复执行业务动作。
                ConsumeConcurrentlyStatus status = ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                for (MessageExt message : messages) {
                    ConsumeConcurrentlyStatus one = handle(target, message);
                    if (one == ConsumeConcurrentlyStatus.RECONSUME_LATER) {
                        status = ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return status;
            });
            consumer.start();
            started.add(consumer);
            log.info("RocketMQ 消费者已启动 topic={} group={} consumer={}",
                    entry.getKey(), props.getConsumerGroup(), target.name());
        }
    }

    @Override
    public void destroy() {
        for (DefaultMQPushConsumer consumer : started) {
            try {
                consumer.shutdown();
            } catch (Exception e) {
                log.warn("RocketMQ 消费者停止异常 group={} err={}", props.getConsumerGroup(), e.toString());
            }
        }
        started.clear();
    }

    /** 一条消息的处理：守卫 → 幂等 → 解密 → 业务消费 → 记账。 */
    ConsumeConcurrentlyStatus handle(EnvelopeConsumer consumer, MessageExt message) {
        String json = new String(message.getBody(), StandardCharsets.UTF_8);
        CipherEnvelope envelope;
        try {
            EnvelopePayloadGuard.validate(json);
            envelope = CipherEnvelope.fromJson(json);
        } catch (RuntimeException e) {
            // 毒消息：结构非法重投多少次都不会成功，直接记账后停止重投（不进 DEAD 重试风暴）
            log.error("密文信封结构非法，丢弃并留痕 topic={} consumer={} err={}",
                    message.getTopic(), consumer.name(), e.getMessage());
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }

        String envelopeId = envelope.envelopeId();
        String name = consumer.name();
        inbox.insertIfAbsent(envelopeId, envelope.bizType(), name);
        List<Map<String, Object>> current = inbox.find(envelopeId, name);
        String status = current.isEmpty() ? "PENDING" : RowValues.asString(current.get(0), "status");
        int attempts = current.isEmpty() ? 0 : RowValues.asInt(current.get(0), "attempts");

        if ("DONE".equals(status) || "DEAD".equals(status)) {
            log.info("密文信封已处理过，跳过（幂等）envelopeId={} consumer={} status={}", envelopeId, name, status);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
        if (attempts >= MAX_ATTEMPTS) {
            inbox.markDead(envelopeId, name, "重试次数已达上限，未再执行");
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }

        try {
            String plaintext = cipher.open(envelope);
            consumer.consume(envelope, plaintext);
            inbox.markDone(envelopeId, name, attempts + 1);
            log.info("密文信封消费成功 envelopeId={} consumer={} bizType={} bizRef={}",
                    envelopeId, name, envelope.bizType(), envelope.bizRef());
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        } catch (Exception e) {
            String err = truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            inbox.markFailed(envelopeId, name, attempts + 1, err);
            log.warn("密文信封消费失败，待重投 envelopeId={} consumer={} attempts={}/{} err={}",
                    envelopeId, name, attempts + 1, MAX_ATTEMPTS, err);
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
    }

    private String truncate(String s) {
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN) + "…";
    }
}
