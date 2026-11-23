package com.company.retail.infrastructure.messaging;

import com.company.retail.common.messaging.AesGcmEnvelopeCipher;
import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeConsumer;
import com.company.retail.common.messaging.EnvelopeTopics;
import com.company.retail.infrastructure.config.MqProperties;
import com.company.retail.infrastructure.mapper.MqEnvelopeInboxMapper;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密文信封消费端单测（ADR-RF-014 §3）。
 *
 * <p>覆盖：正常消费、幂等跳过、失败重投、超上限置 DEAD、毒消息不重投。
 * 持久层用手写内存桩（本仓除 common 外不引 Mockito，避免新增测试依赖）。</p>
 */
class MqEnvelopeDispatcherTest {

    private FakeInbox inbox;
    private MqEnvelopeDispatcher dispatcher;
    private final AesGcmEnvelopeCipher cipher = new AesGcmEnvelopeCipher("test-data-key-0123456789", "v1");
    private final List<String> consumed = new ArrayList<>();

    private final EnvelopeConsumer consumer = new EnvelopeConsumer() {
        @Override
        public String name() {
            return "test-consumer";
        }

        @Override
        public String topic() {
            return EnvelopeTopics.BUREAU;
        }

        @Override
        public void consume(CipherEnvelope envelope, String payloadJson) {
            consumed.add(payloadJson);
        }
    };

    @BeforeEach
    void setUp() {
        inbox = new FakeInbox();
        MqProperties props = new MqProperties();
        props.setEnabled(true);
        props.setNameServer("127.0.0.1:9876");
        dispatcher = new MqEnvelopeDispatcher(props, cipher, inbox, List.of(consumer));
        consumed.clear();
    }

    @Test
    void handle_validEnvelope_consumesAndMarksDone() {
        CipherEnvelope envelope = cipher.seal(EnvelopeTopics.BUREAU, "42", "{\"loanId\":42}", "t");

        ConsumeConcurrentlyStatus status = dispatcher.handle(consumer, message(envelope));

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, status);
        assertEquals(1, consumed.size());
        assertEquals("DONE", inbox.status(envelope.envelopeId(), consumer.name()));
    }

    @Test
    void handle_duplicateEnvelope_isIdempotent() {
        CipherEnvelope envelope = cipher.seal(EnvelopeTopics.BUREAU, "42", "{\"loanId\":42}", "t");

        dispatcher.handle(consumer, message(envelope));
        ConsumeConcurrentlyStatus second = dispatcher.handle(consumer, message(envelope));

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, second);
        assertEquals(1, consumed.size(), "同一信封不得被消费两次");
    }

    @Test
    void handle_businessFailure_reconsumeLater() {
        EnvelopeConsumer failing = new EnvelopeConsumer() {
            @Override
            public String name() {
                return "failing-consumer";
            }

            @Override
            public String topic() {
                return EnvelopeTopics.BUREAU;
            }

            @Override
            public void consume(CipherEnvelope envelope, String payloadJson) {
                throw new IllegalStateException("下游不可用");
            }
        };
        CipherEnvelope envelope = cipher.seal(EnvelopeTopics.BUREAU, "43", "{\"loanId\":43}", "t");

        ConsumeConcurrentlyStatus status = dispatcher.handle(failing, message(envelope));

        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, status);
        assertEquals("FAILED", inbox.status(envelope.envelopeId(), failing.name()));
        assertEquals(1, inbox.attempts(envelope.envelopeId(), failing.name()));
    }

    @Test
    void handle_attemptsReachedLimit_marksDeadAndStops() {
        CipherEnvelope envelope = cipher.seal(EnvelopeTopics.BUREAU, "44", "{\"loanId\":44}", "t");
        inbox.seed(envelope.envelopeId(), consumer.name(), "FAILED", 3);

        ConsumeConcurrentlyStatus status = dispatcher.handle(consumer, message(envelope));

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, status);
        assertEquals("DEAD", inbox.status(envelope.envelopeId(), consumer.name()));
        assertEquals(0, consumed.size(), "已达上限不得再执行业务动作");
    }

    @Test
    void handle_poisonMessage_isDroppedWithoutRetry() {
        MessageExt bad = new MessageExt();
        bad.setTopic(EnvelopeTopics.BUREAU);
        bad.setBody("not-json".getBytes(StandardCharsets.UTF_8));

        ConsumeConcurrentlyStatus status = dispatcher.handle(consumer, bad);

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, status);
        assertEquals(0, consumed.size());
    }

    private MessageExt message(CipherEnvelope envelope) {
        MessageExt m = new MessageExt();
        m.setTopic(EnvelopeTopics.BUREAU);
        m.setBody(envelope.toJson().getBytes(StandardCharsets.UTF_8));
        return m;
    }

    /** 内存版消费幂等表（envelope_id + consumer）。 */
    static final class FakeInbox implements MqEnvelopeInboxMapper {

        private final Map<String, String> statuses = new HashMap<>();
        private final Map<String, Integer> attempts = new HashMap<>();

        private String key(String envelopeId, String consumer) {
            return envelopeId + "|" + consumer;
        }

        @Override
        public int insertIfAbsent(String envelopeId, String topic, String consumer) {
            statuses.putIfAbsent(key(envelopeId, consumer), "PENDING");
            attempts.putIfAbsent(key(envelopeId, consumer), 0);
            return 1;
        }

        @Override
        public List<Map<String, Object>> find(String envelopeId, String consumer) {
            String status = statuses.get(key(envelopeId, consumer));
            if (status == null) {
                return List.of();
            }
            Map<String, Object> row = new HashMap<>();
            row.put("status", status);
            row.put("attempts", attempts.getOrDefault(key(envelopeId, consumer), 0));
            return List.of(row);
        }

        @Override
        public int markDone(String envelopeId, String consumer, int next) {
            statuses.put(key(envelopeId, consumer), "DONE");
            attempts.put(key(envelopeId, consumer), next);
            return 1;
        }

        @Override
        public int markFailed(String envelopeId, String consumer, int next, String err) {
            statuses.put(key(envelopeId, consumer), "FAILED");
            attempts.put(key(envelopeId, consumer), next);
            return 1;
        }

        @Override
        public int markDead(String envelopeId, String consumer, String err) {
            statuses.put(key(envelopeId, consumer), "DEAD");
            return 1;
        }

        void seed(String envelopeId, String consumer, String status, int attempt) {
            statuses.put(key(envelopeId, consumer), status);
            attempts.put(key(envelopeId, consumer), attempt);
        }

        String status(String envelopeId, String consumer) {
            return statuses.get(key(envelopeId, consumer));
        }

        int attempts(String envelopeId, String consumer) {
            return attempts.getOrDefault(key(envelopeId, consumer), 0);
        }
    }
}
