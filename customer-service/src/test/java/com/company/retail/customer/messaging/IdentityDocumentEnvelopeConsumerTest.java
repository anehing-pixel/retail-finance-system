package com.company.retail.customer.messaging;

import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeTopics;
import com.company.retail.customer.app.IdentityDocumentHandler;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 身份核验材料链路密文信封消费端单测（ADR-RF-014）：
 * 载荷解析后只调用一次材料登记处理器；载荷非法直接抛错（不吞异常，交由 broker 重投）。
 */
class IdentityDocumentEnvelopeConsumerTest {

    private static final CipherEnvelope ENVELOPE = new CipherEnvelope(
            "env-1", EnvelopeTopics.IDENTITY_DOCUMENT, "7", "v1", "AES-256-GCM",
            "Y2lwaGVydGV4dA==", "trace-1", "2026-11-23T12:00:00+09:00");

    @Test
    void 主题与消费者名固定() {
        IdentityDocumentEnvelopeConsumer consumer = new IdentityDocumentEnvelopeConsumer(new RecordingHandler());
        assertEquals(EnvelopeTopics.IDENTITY_DOCUMENT, consumer.topic());
        assertEquals("customer-identity-document", consumer.name());
    }

    @Test
    void 载荷解析后调用材料登记处理器一次() {
        RecordingHandler handler = new RecordingHandler();
        IdentityDocumentEnvelopeConsumer consumer = new IdentityDocumentEnvelopeConsumer(handler);

        consumer.consume(ENVELOPE, "{\"verificationId\":7,\"documentType\":\"DRIVER_LICENSE\","
                + "\"attachmentId\":9001,\"expiryDate\":\"2030-04-01\","
                + "\"maskedNumber\":\"****1234\",\"traceId\":\"trace-1\"}");

        assertEquals(1, handler.commands.size());
        IdentityDocumentHandler.DocumentCommand command = handler.commands.get(0);
        assertEquals(7L, command.verificationId());
        assertEquals("DRIVER_LICENSE", command.documentType());
        assertEquals(9001L, command.attachmentId().longValue());
        assertEquals(LocalDate.of(2030, 4, 1), command.expiryDate());
        assertEquals("****1234", command.maskedNumber());
        assertEquals("trace-1", command.traceId());
    }

    @Test
    void 载荷非法时抛错且不调用处理器() {
        RecordingHandler handler = new RecordingHandler();
        IdentityDocumentEnvelopeConsumer consumer = new IdentityDocumentEnvelopeConsumer(handler);

        assertThrows(IllegalArgumentException.class, () -> consumer.consume(ENVELOPE, "not-json"));
        // 缺 verificationId
        assertThrows(IllegalArgumentException.class,
                () -> consumer.consume(ENVELOPE, "{\"documentType\":\"DRIVER_LICENSE\"}"));
        // 缺 documentType
        assertThrows(IllegalArgumentException.class,
                () -> consumer.consume(ENVELOPE, "{\"verificationId\":7}"));
        assertEquals(0, handler.commands.size());
    }

    @Test
    void 载荷往返序列化后字段不丢() {
        IdentityDocumentHandler.DocumentCommand command = new IdentityDocumentHandler.DocumentCommand(
                7L, "DRIVER_LICENSE", 9001L, LocalDate.of(2030, 4, 1), "****1234", "trace-1");
        IdentityDocumentHandler.DocumentCommand parsed =
                IdentityDocumentHandler.fromPayloadJson(IdentityDocumentHandler.payloadJson(command));
        assertEquals(command, parsed);
    }

    /** 材料登记动作桩：记录调用次数与参数，绕开数据库。 */
    private static final class RecordingHandler extends IdentityDocumentHandler {

        private final List<IdentityDocumentHandler.DocumentCommand> commands = new ArrayList<>();

        RecordingHandler() {
            super(null);
        }

        @Override
        public void register(IdentityDocumentHandler.DocumentCommand command) {
            commands.add(command);
        }
    }
}
