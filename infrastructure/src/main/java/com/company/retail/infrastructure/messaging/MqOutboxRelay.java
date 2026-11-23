package com.company.retail.infrastructure.messaging;

import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeCipher;
import com.company.retail.common.messaging.EnvelopePublisher;
import com.company.retail.common.messaging.EnvelopeTopics;
import com.company.retail.common.persistence.RowValues;
import com.company.retail.infrastructure.config.MqProperties;
import com.company.retail.infrastructure.mapper.OutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用中继：outbox 业务事件 → RocketMQ（ADR-RF-014 §4）。
 *
 * <p><b>为什么不是「outbox 直接发 MQ」</b>：状态变更与事件产生必须在同一本地事务落库
 * （B-11 核心账务不拆、INV 不变量），MQ 是其后的投递通道；中继只做「已落库事件的搬运」，
 * 不参与事务，因此不引入分布式事务，也不改变任何既有事务边界。</p>
 *
 * <p><b>中继载荷同样是密文信封</b>：broker 上只允许出现信封（密文 + 业务键），
 * 业务载荷一律在密文内部。outbox 事件本身已过 {@code EventPayloadGuard} 白名单（非 PII），
 * 这里仍然加密，是为了让「MQ 上只有信封」这条纪律没有例外分支。</p>
 *
 * <p><b>幂等与不重不漏</b>：{@code relayed_at} 标记保证每条事件只中继一次（投递失败不标记，
 * 下轮重试）；消费侧再按 {@code (event_id, consumer)} 去重，与直投路径共用同一张幂等表
 * ——两条路径同时存在也不会重复执行业务动作。</p>
 */
public class MqOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(MqOutboxRelay.class);

    private final MqProperties props;
    private final OutboxMapper mapper;
    private final EnvelopeCipher cipher;
    private final EnvelopePublisher publisher;

    public MqOutboxRelay(MqProperties props, OutboxMapper mapper,
                          EnvelopeCipher cipher, EnvelopePublisher publisher) {
        this.props = props;
        this.mapper = mapper;
        this.cipher = cipher;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${retail.mq.relay-interval-ms:1000}")
    public void relay() {
        if (!publisher.enabled()) {
            return;
        }
        List<Map<String, Object>> rows;
        try {
            rows = mapper.selectUnrelayedEvents(props.getRelayBatchLimit());
        } catch (Exception e) {
            log.warn("中继读取 outbox 异常 err={}", e.toString());
            return;
        }
        for (Map<String, Object> row : rows) {
            long eventId = RowValues.asLong(row, "event_id");
            String eventType = RowValues.asString(row, "event_type");
            String aggregateKey = RowValues.asString(row, "aggregate_key");
            String payload = RowValues.asString(row, "payload");
            try {
                String eventJson = buildEventJson(eventId, eventType, aggregateKey, payload);
                CipherEnvelope envelope = cipher.seal(EnvelopeTopics.EVENT_RELAY,
                        String.valueOf(eventId), eventJson, null);
                publisher.publish(EnvelopeTopics.EVENT_RELAY, envelope);
                mapper.markRelayed(eventId);
                log.info("outbox 事件已中继到 MQ event_id={} event_type={} aggregate_key={}",
                        eventId, eventType, aggregateKey);
            } catch (Exception e) {
                // 中继失败不标记 relayed_at：下轮重试（不丢事件，也不阻塞其余事件）
                log.warn("outbox 事件中继失败，下轮重试 event_id={} event_type={} err={}",
                        eventId, eventType, e.toString());
                return;
            }
        }
    }

    /** 中继载荷：事件信封（eventId / eventType / aggregateKey / payload / occurredAt）。 */
    private String buildEventJson(long eventId, String eventType, String aggregateKey, String payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", eventId);
        m.put("eventType", eventType);
        m.put("aggregateKey", aggregateKey);
        m.put("payload", payload == null ? "{}" : payload);
        m.put("occurredAt", java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Tokyo")).toString());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("中继载荷序列化失败", e);
        }
    }
}
