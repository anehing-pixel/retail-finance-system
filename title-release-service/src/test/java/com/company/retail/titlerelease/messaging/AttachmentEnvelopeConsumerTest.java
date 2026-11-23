package com.company.retail.titlerelease.messaging;

import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeTopics;
import com.company.retail.common.messaging.MqEnvelopeException;
import com.company.retail.titlerelease.persistence.AttachmentRepository;
import com.company.retail.titlerelease.storage.LocalStorageAttachmentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 附件密文信封消费端（ADR-RF-014）：消费后落盘并登记「待登记」占位行；载荷非法一律抛错。
 *
 * <p>消费端只拿到已解密的载荷 JSON，不接触密钥；解密与投递幂等由基础设施层承担。</p>
 */
class AttachmentEnvelopeConsumerTest {

    @TempDir
    Path tempDir;

    private LocalStorageAttachmentStorage storage;
    private StubAttachmentRepository repository;
    private AttachmentEnvelopeConsumer consumer;

    @BeforeEach
    void setUp() {
        storage = new LocalStorageAttachmentStorage(tempDir.toString(), "test-token-secret", 1024);
        repository = new StubAttachmentRepository();
        consumer = new AttachmentEnvelopeConsumer(provider(storage), repository);
    }

    // ---------- 正常消费：落盘 + 待登记行 ----------

    @Test
    void consume_writesFileAndRegistersPendingRow() {
        String key = "release-order/2026/11/upload.txt";
        byte[] body = "release-document-content".getBytes(StandardCharsets.UTF_8);
        String payload = new AttachmentEnvelopePayload(key, "text/plain", body.length,
                Base64.getEncoder().encodeToString(body), "STAFF001", "trace-1").toJson();

        consumer.consume(envelope(key), payload);

        // 落盘：内容与字节一致
        assertArrayEquals(body, storage.get(key));
        // 待登记占位行：sha256 / 大小 / 上传者照实写
        assertEquals(1, repository.pendingInserts);
        assertEquals(key, repository.pendingObjectKey);
        assertEquals(storage.head(key).sha256(), repository.pendingSha256);
        assertEquals(body.length, repository.pendingSize);
        assertEquals("STAFF001", repository.pendingUploadedBy);
        assertEquals("trace-1", repository.pendingTraceId);
    }

    @Test
    void consume_isIdempotentOnPendingRow_registersOnlyOnce() {
        String key = "release-order/2026/11/upload.txt";
        byte[] body = "release-document-content".getBytes(StandardCharsets.UTF_8);
        String payload = new AttachmentEnvelopePayload(key, "text/plain", body.length,
                Base64.getEncoder().encodeToString(body), "STAFF001", "trace-1").toJson();

        consumer.consume(envelope(key), payload);
        consumer.consume(envelope(key), payload);

        // 占位行由仓储层幂等插入（同一 objectKey 不重复插入）：调用 2 次，实际只留 1 行
        assertEquals(2, repository.pendingInsertCalls);
        assertEquals(1, repository.pendingInserts);
    }

    @Test
    void topicAndName_areFixed() {
        assertEquals(EnvelopeTopics.ATTACHMENT, consumer.topic());
        assertEquals("title-release-attachment", consumer.name());
    }

    // ---------- 非法载荷：一律抛错（不吞异常，交给消费端机制重试/DEAD） ----------

    @Test
    void consume_payloadNotJson_throws() {
        assertThrows(MqEnvelopeException.class,
                () -> consumer.consume(envelope("k.txt"), "not-json"));
    }

    @Test
    void consume_payloadMissingFields_throws() {
        assertThrows(MqEnvelopeException.class,
                () -> consumer.consume(envelope("k.txt"), "{\"objectKey\":\"k.txt\"}"));
    }

    @Test
    void consume_objectKeyNotMatchEnvelope_throws() {
        String key = "release-order/2026/11/upload.txt";
        byte[] body = "doc".getBytes(StandardCharsets.UTF_8);
        String payload = new AttachmentEnvelopePayload(key, "text/plain", body.length,
                Base64.getEncoder().encodeToString(body), "STAFF001", "trace-1").toJson();

        assertThrows(MqEnvelopeException.class,
                () -> consumer.consume(envelope("other.txt"), payload));
        assertEquals(0, repository.pendingInserts);
    }

    @Test
    void consume_contentLengthMismatch_throws() {
        String key = "release-order/2026/11/upload.txt";
        byte[] body = "doc".getBytes(StandardCharsets.UTF_8);
        String payload = new AttachmentEnvelopePayload(key, "text/plain", body.length + 1,
                Base64.getEncoder().encodeToString(body), "STAFF001", "trace-1").toJson();

        assertThrows(MqEnvelopeException.class, () -> consumer.consume(envelope(key), payload));
        assertEquals(0, repository.pendingInserts);
    }

    @Test
    void consume_contentNotBase64_throws() {
        String key = "release-order/2026/11/upload.txt";
        String payload = new AttachmentEnvelopePayload(key, "text/plain", 3,
                "!!!not-base64!!!", "STAFF001", "trace-1").toJson();

        assertThrows(MqEnvelopeException.class, () -> consumer.consume(envelope(key), payload));
        assertEquals(0, repository.pendingInserts);
    }

    // ---------- 辅助 ----------

    private static CipherEnvelope envelope(String bizRef) {
        return new CipherEnvelope("env-1", EnvelopeTopics.ATTACHMENT, bizRef, "v1",
                "AES-256-GCM", "Y2lwaGVydGV4dA==", "trace-1", "2026-11-23T09:00:00+09:00");
    }

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

    /** 附件仓储桩（记录待登记行插入参数，绕开 DB）。 */
    private static final class StubAttachmentRepository extends AttachmentRepository {

        int pendingInserts;
        int pendingInsertCalls;
        final Set<String> insertedKeys = new HashSet<>();
        String pendingObjectKey;
        String pendingSha256;
        long pendingSize;
        String pendingUploadedBy;
        String pendingTraceId;

        StubAttachmentRepository() {
            super(null);
        }

        @Override
        public boolean insertPendingIfAbsent(String objectKey, String sha256, long sizeBytes,
                                             String uploadedBy, String traceId) {
            pendingInsertCalls++;
            if (!insertedKeys.add(objectKey)) {
                // 已存在同一 objectKey 的占位行：DB 侧不重复插入
                return false;
            }
            pendingInserts++;
            pendingObjectKey = objectKey;
            pendingSha256 = sha256;
            pendingSize = sizeBytes;
            pendingUploadedBy = uploadedBy;
            pendingTraceId = traceId;
            return true;
        }

        @Override
        public Optional<AttachmentRepository.AttachmentRow> findPending(String objectKey) {
            return Optional.empty();
        }

        @Override
        public Optional<AttachmentRepository.AttachmentRow> findRegisteredByRelativePath(String relativePath) {
            return Optional.empty();
        }

        @Override
        public boolean promotePending(String objectKey, String bizType, String bizId) {
            return true;
        }
    }
}
