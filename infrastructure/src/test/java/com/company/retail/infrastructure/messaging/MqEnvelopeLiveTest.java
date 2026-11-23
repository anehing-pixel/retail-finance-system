package com.company.retail.infrastructure.messaging;

import com.company.retail.common.messaging.AesGcmEnvelopeCipher;
import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeConsumer;
import com.company.retail.common.messaging.EnvelopeCipher;
import com.company.retail.common.messaging.EnvelopeTopics;
import com.company.retail.infrastructure.config.MqProperties;
import com.company.retail.infrastructure.mapper.MqEnvelopeInboxMapper;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * 真实 broker 活体联调（ADR-RF-014 的验收证据）。
 *
 * <p><b>默认跳过</b>：常规构建不要求本机跑着 broker——加 {@code -Drf.mq.live=true} 才启用；
 * 启用后 broker 不在则**直接失败**（不允许静默跳过，那等于没验证）。</p>
 *
 * <p><b>验证四件事</b>：
 * ① 真发真收（真实 RocketMQ 5 broker，非 mock）；
 * ② <b>broker 上不落明文</b>——用独立消费组回读原始消息体，断言明文标记串不存在；
 * ③ 消费端解密后业务载荷正确；
 * ④ 重复投递只消费一次（幂等）。</p>
 *
 * <p><b>主题创建时序</b>：broker 开启 autoCreateTopicEnable，主题由「第一条消息」创建；
 * 消费者若在主题不存在时订阅，要等下一次心跳（默认 30s）才拿到主题元数据。
 * 因此先发一条预热信封把主题建出来，再启动消费者，再发真实信封——联调才是确定性的。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MqEnvelopeLiveTest {

    private static final String NAMESRV = "127.0.0.1:9876";
    private static final String TOPIC = "RF_ENVELOPE_LIVE";
    private static final String PII_MARKER = "张三百PII_MARKER_99110";
    private static final String DATA_KEY = "live-test-data-key-0123456789";

    private static MqProperties props;
    private static RocketMqEnvelopePublisher publisher;
    private static MqEnvelopeDispatcher dispatcher;
    private static final EnvelopeCipher cipher = new AesGcmEnvelopeCipher(DATA_KEY, "v1");
    private static final List<String> received = new CopyOnWriteArrayList<>();
    private static final CountDownLatch liveLatch = new CountDownLatch(1);

    @BeforeAll
    static void setUp() throws Exception {
        if (!Boolean.getBoolean("rf.mq.live")) {
            abort("未开启真实 broker 联调（-Drf.mq.live=true 启用；本机需先起 RocketMQ）");
        }
        assertTrue(portOpen(9876), "真实 RocketMQ 未启动：namesrv 127.0.0.1:9876 不通（禁止静默跳过）");
        assertTrue(portOpen(10911), "真实 RocketMQ 未启动：broker 127.0.0.1:10911 不通（禁止静默跳过）");

        props = new MqProperties();
        props.setEnabled(true);
        props.setNameServer(NAMESRV);
        props.setProducerGroup("rf-live-producer");
        props.setConsumerGroup("rf-live-consumer-" + System.nanoTime());
        props.setSendTimeoutMs(5000);

        publisher = new RocketMqEnvelopePublisher(props);
        publisher.afterPropertiesSet();

        // 预热：把主题建出来（broker autoCreateTopicEnable）
        publisher.publish(TOPIC, cipher.seal(TOPIC, "warmup", "{\"kind\":\"warmup\"}", "warmup-trace"));
        Thread.sleep(2000);

        dispatcher = new MqEnvelopeDispatcher(props, cipher, new InMemoryInbox(), List.of(new EnvelopeConsumer() {
            @Override
            public String name() {
                return "live-consumer";
            }

            @Override
            public String topic() {
                return TOPIC;
            }

            @Override
            public void consume(CipherEnvelope envelope, String payloadJson) {
                received.add(payloadJson);
                if (payloadJson.contains("\"kind\":\"live\"")) {
                    liveLatch.countDown();
                }
            }
        }));
        dispatcher.afterPropertiesSet();
        // 让消费者完成订阅与队列分配
        Thread.sleep(4000);
    }

    @AfterAll
    static void tearDown() {
        if (dispatcher != null) {
            dispatcher.destroy();
        }
        if (publisher != null) {
            publisher.destroy();
        }
    }

    @Test
    @Order(1)
    void live_publishConsume_decryptsAndIdempotent() throws Exception {
        String payload = "{\"kind\":\"live\",\"customer\":\"" + PII_MARKER + "\",\"loanId\":99110}";
        CipherEnvelope envelope = cipher.seal(TOPIC, "99110", payload, "live-trace");

        publisher.publish(TOPIC, envelope);

        assertTrue(liveLatch.await(45, TimeUnit.SECONDS), "真实 broker 未在 45 秒内投递到消费者");
        assertEquals(1, countLive(), "真实信封应被消费一次");

        // ② broker 上的原始消息体不得含明文（用独立消费组回读全部消息体逐条断言）
        List<String> rawBodies = readRawMessages();
        assertFalse(rawBodies.isEmpty(), "未能从 broker 回读到原始消息体");
        for (String body : rawBodies) {
            assertFalse(body.contains(PII_MARKER), "broker 上出现明文个人信息——违反 ADR-RF-014 密文信封红线");
            assertTrue(body.contains("ciphertext"), "broker 上的消息体应为密文信封");
        }

        // ④ 重复投递：同一信封再投一次，消费端不得重复执行业务动作
        publisher.publish(TOPIC, envelope);
        Thread.sleep(5000);
        assertEquals(1, countLive(), "重复投递不得重复消费（幂等失效）");
    }

    private static long countLive() {
        return received.stream().filter(p -> p.contains("\"kind\":\"live\"")).count();
    }

    /** 用独立消费组回读 broker 上的原始消息体（验证「落的是密文」）。 */
    private List<String> readRawMessages() throws Exception {
        DefaultMQPushConsumer raw = new DefaultMQPushConsumer("rf-live-raw-" + System.nanoTime());
        raw.setNamesrvAddr(NAMESRV);
        raw.setInstanceName("rf-live-raw");
        raw.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        raw.subscribe(TOPIC, "*");
        List<String> bodies = new CopyOnWriteArrayList<>();
        CountDownLatch got = new CountDownLatch(1);
        raw.registerMessageListener((MessageListenerConcurrently) (messages, ctx) -> {
            for (MessageExt m : messages) {
                bodies.add(new String(m.getBody(), StandardCharsets.UTF_8));
            }
            got.countDown();
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        raw.start();
        try {
            got.await(20, TimeUnit.SECONDS);
            Thread.sleep(2000);
            return new ArrayList<>(bodies);
        } finally {
            raw.shutdown();
        }
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 内存版消费幂等表（联调不落库：只验「真发真收 + 密文 + 幂等」三件事）。 */
    static final class InMemoryInbox implements MqEnvelopeInboxMapper {

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
    }
}
