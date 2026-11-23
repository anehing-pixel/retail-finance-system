package com.company.retail.common.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密文信封单测（ADR-RF-014 §3）。
 *
 * <p>覆盖：加解密往返、业务键绑定校验、载荷守卫默认拒绝、信封顶层键白名单。</p>
 */
class CipherEnvelopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EnvelopeCipher cipher = new AesGcmEnvelopeCipher("test-data-key-0123456789", "v1");

    @Test
    void sealThenOpen_returnsOriginalPayload() {
        String payload = "{\"contractId\":123,\"name\":\"不该出现在信封里\"}";
        CipherEnvelope envelope = cipher.seal(EnvelopeTopics.DELIVERY, "123", payload, "trace-1");

        String opened = cipher.open(envelope);

        assertEquals(payload, opened);
        assertEquals(EnvelopeTopics.DELIVERY, envelope.bizType());
        assertEquals("123", envelope.bizRef());
        assertEquals("v1", envelope.keyVersion());
        assertEquals(AesGcmEnvelopeCipher.ALG, envelope.alg());
        assertTrue(envelope.envelopeId() != null && !envelope.envelopeId().isBlank());
    }

    @Test
    void open_rejectsCrossBusinessRebinding() {
        String payload = "{\"contractId\":123}";
        CipherEnvelope envelope = cipher.seal(EnvelopeTopics.DELIVERY, "123", payload, "trace-1");

        // 同一份密文换一个 bizRef（信封声明被篡改）→ 必须拒绝
        CipherEnvelope tampered = new CipherEnvelope(envelope.envelopeId(), envelope.bizType(),
                "999", envelope.keyVersion(), envelope.alg(), envelope.ciphertext(),
                envelope.traceId(), envelope.issuedAt());

        MqEnvelopeException e = assertThrows(MqEnvelopeException.class, () -> cipher.open(tampered));
        assertTrue(e.getMessage().contains("跨业务重放"));
    }

    @Test
    void open_rejectsTamperedCiphertext() {
        CipherEnvelope envelope = cipher.seal(EnvelopeTopics.BUREAU, "1", "{\"loanId\":1}", "t");
        CipherEnvelope tampered = new CipherEnvelope(envelope.envelopeId(), envelope.bizType(),
                envelope.bizRef(), envelope.keyVersion(), envelope.alg(),
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                envelope.traceId(), envelope.issuedAt());

        assertThrows(MqEnvelopeException.class, () -> cipher.open(tampered));
    }

    @Test
    void envelopeJson_hasExactlyEightAllowedTopLevelKeys() throws Exception {
        CipherEnvelope envelope = cipher.seal(EnvelopeTopics.ATTACHMENT, "key-1",
                "{\"bytes\":\"AA==\"}", "t");

        String json = envelope.toJson();
        EnvelopePayloadGuard.validate(json);

        JsonNode node = MAPPER.readTree(json);
        assertEquals(EnvelopePayloadGuard.ALLOWED_KEYS.size(), node.size());
        node.fieldNames().forEachRemaining(k ->
                assertTrue(EnvelopePayloadGuard.ALLOWED_KEYS.contains(k), "白名单外的键：" + k));
        // 密文不得与明文相同
        assertNotEquals("{\"bytes\":\"AA==\"}", node.path("ciphertext").asText());
    }

    @Test
    void guard_rejectsPlaintextPayloadKey() {
        String json = "{\"envelopeId\":\"e1\",\"bizType\":\"DELIVERY\",\"bizRef\":\"1\","
                + "\"keyVersion\":\"v1\",\"alg\":\"AES-256-GCM\",\"ciphertext\":\"QUFB\","
                + "\"traceId\":\"t\",\"issuedAt\":\"2026-11-23T00:00:00+09:00\",\"payload\":\"{\\\"name\\\":\\\"张三\\\"}\"}";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EnvelopePayloadGuard.validate(json));
        assertTrue(e.getMessage().contains("payload"));
    }

    @Test
    void guard_reportsPlaintextPiiKeysExplicitly() {
        // 命中明文 PII 键时，报错必须一眼可辨（区分「明文 PII 键」与「未知键」）——
        // 这是「MQ 上只允许密文信封」红线最常见的踩坑形态，排查成本必须压到最低。
        String pii = "{\"envelopeId\":\"e1\",\"bizType\":\"DELIVERY\",\"bizRef\":\"1\","
                + "\"keyVersion\":\"v1\",\"alg\":\"AES-256-GCM\",\"ciphertext\":\"QUFB\","
                + "\"traceId\":\"t\",\"issuedAt\":\"x\",\"idNumber\":\"123\"}";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EnvelopePayloadGuard.validate(pii));
        assertTrue(e.getMessage().contains("明文 PII"), e.getMessage());
        assertTrue(e.getMessage().contains("idNumber"), e.getMessage());
    }

    @Test
    void guard_rejectsPlaintextPiiKeysAndBadCiphertext() {
        String pii = "{\"envelopeId\":\"e1\",\"bizType\":\"DELIVERY\",\"bizRef\":\"1\","
                + "\"keyVersion\":\"v1\",\"alg\":\"AES-256-GCM\",\"ciphertext\":\"QUFB\","
                + "\"traceId\":\"t\",\"issuedAt\":\"x\",\"idNumber\":\"123\"}";
        assertThrows(IllegalArgumentException.class, () -> EnvelopePayloadGuard.validate(pii));

        String badCipher = "{\"envelopeId\":\"e1\",\"bizType\":\"DELIVERY\",\"bizRef\":\"1\","
                + "\"keyVersion\":\"v1\",\"alg\":\"AES-256-GCM\",\"ciphertext\":\"不是 base64!!\","
                + "\"traceId\":\"t\",\"issuedAt\":\"x\"}";
        assertThrows(IllegalArgumentException.class, () -> EnvelopePayloadGuard.validate(badCipher));
    }
}
