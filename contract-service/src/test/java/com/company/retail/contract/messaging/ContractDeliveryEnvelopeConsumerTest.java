package com.company.retail.contract.messaging;

import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeTopics;
import com.company.retail.contract.application.DeliveryHandler;
import com.company.retail.contract.integration.HttpDeliveryChannelAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 送达链路密文信封消费端单测（ADR-RF-014）：
 * 载荷解析后只调用一次送达处理器；载荷非法直接抛错（不吞异常，交由 broker 重投）。
 */
class ContractDeliveryEnvelopeConsumerTest {

    private static final CipherEnvelope ENVELOPE = new CipherEnvelope(
            "env-1", EnvelopeTopics.DELIVERY, "42", "v1", "AES-256-GCM",
            "Y2lwaGVydGV4dA==", "trace-1", "2026-11-23T12:00:00+09:00");

    @Test
    void 主题与消费者名固定() {
        ContractDeliveryEnvelopeConsumer consumer = new ContractDeliveryEnvelopeConsumer(new RecordingHandler());
        assertEquals(EnvelopeTopics.DELIVERY, consumer.topic());
        assertEquals("contract-delivery", consumer.name());
    }

    @Test
    void 载荷解析后调用送达处理器一次() {
        RecordingHandler handler = new RecordingHandler();
        ContractDeliveryEnvelopeConsumer consumer = new ContractDeliveryEnvelopeConsumer(handler);

        consumer.consume(ENVELOPE, "{\"contractId\":42,\"docType\":\"CONTRACT_DOCUMENT\","
                + "\"deliveryMethod\":\"ELECTRONIC\",\"templateVersion\":\"v1\","
                + "\"attachmentId\":9001,\"contentRef\":\"attachment:9001\","
                + "\"contentText\":\"\",\"traceId\":\"trace-1\"}");

        assertEquals(1, handler.commands.size());
        DeliveryHandler.DeliveryCommand command = handler.commands.get(0);
        assertEquals(42L, command.contractId());
        assertEquals("CONTRACT_DOCUMENT", command.docType());
        assertEquals("ELECTRONIC", command.deliveryMethod());
        assertEquals("v1", command.templateVersion());
        assertEquals(9001L, command.attachmentId().longValue());
        assertEquals("attachment:9001", command.contentRef());
        assertEquals("trace-1", command.traceId());
    }

    @Test
    void 载荷非法时抛错且不调用处理器() {
        RecordingHandler handler = new RecordingHandler();
        ContractDeliveryEnvelopeConsumer consumer = new ContractDeliveryEnvelopeConsumer(handler);

        assertThrows(IllegalArgumentException.class, () -> consumer.consume(ENVELOPE, "not-json"));
        // 缺 contractId
        assertThrows(IllegalArgumentException.class,
                () -> consumer.consume(ENVELOPE, "{\"docType\":\"CONTRACT_DOCUMENT\"}"));
        // 缺 docType/deliveryMethod
        assertThrows(IllegalArgumentException.class,
                () -> consumer.consume(ENVELOPE, "{\"contractId\":42}"));
        assertEquals(0, handler.commands.size());
    }

    @Test
    void 载荷往返序列化后字段不丢() {
        DeliveryHandler.DeliveryCommand command = DeliveryHandler.DeliveryCommand.of(
                42L, "CONTRACT_DOCUMENT", "ELECTRONIC", "v1", 9001L, "trace-1");
        DeliveryHandler.DeliveryCommand parsed =
                DeliveryHandler.fromPayloadJson(DeliveryHandler.payloadJson(command));
        assertEquals(command, parsed);
    }

    /** 送达动作桩：记录调用次数与参数，绕开真实送达通道。 */
    private static final class RecordingHandler extends DeliveryHandler {

        private final List<DeliveryHandler.DeliveryCommand> commands = new ArrayList<>();

        RecordingHandler() {
            super(provider(null));
        }

        @Override
        public void deliver(DeliveryHandler.DeliveryCommand command) {
            commands.add(command);
        }
    }

    /** ObjectProvider 桩（无 Mockito 依赖；与本仓既有测试同款）。 */
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }
}
