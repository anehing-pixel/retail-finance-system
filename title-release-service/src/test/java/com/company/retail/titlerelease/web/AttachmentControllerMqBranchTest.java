package com.company.retail.titlerelease.web;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeCipher;
import com.company.retail.common.messaging.EnvelopePublisher;
import com.company.retail.common.messaging.EnvelopeTopics;
import com.company.retail.titlerelease.messaging.AttachmentEnvelopePayload;
import com.company.retail.titlerelease.persistence.AttachmentRepository;
import com.company.retail.titlerelease.storage.LocalStorageAttachmentStorage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附件端点的 MQ 分支（ADR-RF-014）：控制器把密文扔进 MQ 的三条分支与 register 的受理语义。
 *
 * <ul>
 *   <li>MQ 开启且未超内联上限 → 上传立即受理（accepted + envelopeId），<b>不写盘</b>；</li>
 *   <li>MQ 关闭 → 行为与改造前完全一致（同步写盘 + 返回 sha256）；</li>
 *   <li>MQ 开启但超内联上限 → 回落同步直传（行为不变）；</li>
 *   <li>register：消费端已落盘 → 待登记行转正；尚未落盘 → HTTP 202 受理（不产生第二行）。</li>
 * </ul>
 */
class AttachmentControllerMqBranchTest {

    @TempDir
    Path tempDir;

    private LocalStorageAttachmentStorage storage;
    private StubAttachmentRepository repository;
    private StubEnvelopePublisher publisher;
    private StubEnvelopeCipher cipher;
    private AtomicInteger httpStatus;

    @BeforeEach
    void setUp() {
        storage = new LocalStorageAttachmentStorage(tempDir.toString(), "test-token-secret", 1024);
        repository = new StubAttachmentRepository();
        publisher = new StubEnvelopePublisher(true);
        cipher = new StubEnvelopeCipher();
        httpStatus = new AtomicInteger(200);
        RequestContextHolder.setRequestAttributes(servletAttributes(httpStatus));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ---------- 分支②：MQ 开启 → 投 MQ 立即受理，不写盘 ----------

    @Test
    void upload_mqEnabled_acceptedAndNotWrittenToDisk() {
        AttachmentController controller = controller(2L * 1024 * 1024);
        String key = "release-order/2026/11/mq.txt";
        byte[] body = "release-document-content".getBytes(StandardCharsets.UTF_8);

        String url = presignedUrl(key, body.length);
        var response = controller.upload(key, "text/plain", body.length,
                Long.parseLong(param(url, "expiresAt")), param(url, "signature"), body);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("accepted"));
        assertNotNull(response.getBody().get("envelopeId"));
        assertEquals(key, response.getBody().get("objectKey"));
        // 关键：未落盘（落盘由消费端异步做）
        assertNull(storage.get(key));
        // 投递到附件主题一次
        assertEquals(1, publisher.published.size());
        assertEquals(EnvelopeTopics.ATTACHMENT, publisher.topics.get(0));
        // 载荷：对象键 + 长度 + base64 字节（在密文内部）
        AttachmentEnvelopePayload payload = AttachmentEnvelopePayload.fromJson(cipher.sealedPayload);
        assertEquals(key, payload.objectKey());
        assertEquals(body.length, payload.contentLength());
        assertEquals(Base64.getEncoder().encodeToString(body), payload.contentBase64());
        assertEquals(EnvelopeTopics.ATTACHMENT, cipher.sealedBizType);
        assertEquals(key, cipher.sealedBizRef);
    }

    // ---------- 分支①：MQ 关闭 → 保持原行为 ----------

    @Test
    void upload_mqDisabled_keepsSyncBehavior() {
        publisher = new StubEnvelopePublisher(false);
        AttachmentController controller = controller(2L * 1024 * 1024);
        String key = "release-order/2026/11/sync.txt";
        byte[] body = "release-document-content".getBytes(StandardCharsets.UTF_8);

        String url = presignedUrl(key, body.length);
        var response = controller.upload(key, "text/plain", body.length,
                Long.parseLong(param(url, "expiresAt")), param(url, "signature"), body);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(storage.head(key).sha256(), response.getBody().get("sha256"));
        assertEquals(body.length, ((Number) response.getBody().get("size")).intValue());
        assertNull(response.getBody().get("accepted"));
        assertTrue(storage.get(key) != null);
        assertEquals(0, publisher.published.size());
    }

    // ---------- 分支③：超限回落同步直传 ----------

    @Test
    void upload_exceedsInlineLimit_fallsBackToSync() {
        AttachmentController controller = controller(4L);
        String key = "release-order/2026/11/big.txt";
        byte[] body = "0123456789".getBytes(StandardCharsets.UTF_8);

        String url = presignedUrl(key, body.length);
        var response = controller.upload(key, "text/plain", body.length,
                Long.parseLong(param(url, "expiresAt")), param(url, "signature"), body);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(storage.head(key).sha256(), response.getBody().get("sha256"));
        assertTrue(storage.get(key) != null);
        // 超限文件不投 MQ（避免撞 RocketMQ 4MB 单消息上限）
        assertEquals(0, publisher.published.size());
    }

    // ---------- register：MQ 开启且消费端尚未落盘 → 202 受理 ----------

    @Test
    void register_mqEnabledAndNotLanded_returnsAccepted() {
        AttachmentController controller = controller(2L * 1024 * 1024);
        String key = "release-order/2026/11/mq.txt";

        var result = controller.register(new AttachmentController.RegisterRequest(
                key, "a".repeat(64), 10, "release_order", "1"));

        assertEquals(202, httpStatus.get());
        assertEquals(Boolean.TRUE, result.getData().get("accepted"));
        assertEquals("UPLOAD_PENDING", result.getData().get("reason"));
        assertEquals(0, repository.inserts);
    }

    // ---------- register：MQ 开启且已有待登记行 → 转正 ----------

    @Test
    void register_mqEnabledWithPendingRow_promotesAndIsIdempotent() {
        AttachmentController controller = controller(2L * 1024 * 1024);
        String key = "release-order/2026/11/mq.txt";
        String sha256 = "b".repeat(64);
        repository.pending = new AttachmentRepository.AttachmentRow(
                42L, "PENDING_UPLOAD", key, key, sha256, 10, "STAFF001", "trace-1");

        var result = controller.register(new AttachmentController.RegisterRequest(
                key, sha256, 10, "release_order", "1"));

        assertEquals(42L, ((Number) result.getData().get("attachmentId")).longValue());
        assertEquals("local", result.getData().get("provider"));
        assertEquals(1, repository.promotes);
        assertEquals("release_order", repository.lastBizType);
        assertEquals("1", repository.lastBizId);
        assertEquals(0, repository.inserts);

        // 重复 register：幂等，不产生第二行
        var again = controller.register(new AttachmentController.RegisterRequest(
                key, sha256, 10, "release_order", "1"));
        assertEquals(42L, ((Number) again.getData().get("attachmentId")).longValue());
        assertEquals(0, repository.inserts);
    }

    @Test
    void register_mqEnabledShaMismatch_rejected() {
        AttachmentController controller = controller(2L * 1024 * 1024);
        String key = "release-order/2026/11/mq.txt";
        repository.pending = new AttachmentRepository.AttachmentRow(
                42L, "PENDING_UPLOAD", key, key, "c".repeat(64), 10, "STAFF001", "trace-1");

        assertThrows(BusinessException.class, () -> controller.register(
                new AttachmentController.RegisterRequest(key, "d".repeat(64), 10, "release_order", "1")));
        assertEquals(0, repository.promotes);
        assertEquals(0, repository.inserts);
    }

    // ---------- 辅助 ----------

    private AttachmentController controller(long maxInlineBytes) {
        return new AttachmentController(provider(storage), provider(storage), repository,
                provider(publisher), provider(cipher), maxInlineBytes);
    }

    /** 预签名 URL 只生成一次：签名绑定 key + 类型 + 长度 + expiresAt，拆成两次调用会拿到不一致的时效。 */
    private String presignedUrl(String key, long contentLength) {
        return storage.presignUpload(key, "text/plain", contentLength, Duration.ofSeconds(60)).uploadUrl();
    }

    private static String param(String url, String name) {
        for (String part : url.substring(url.indexOf('?') + 1).split("&")) {
            if (part.startsWith(name + "=")) {
                return part.substring(name.length() + 1);
            }
        }
        throw new IllegalStateException("参数缺失：" + name);
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

    /** 最小 Servlet 请求/响应桩（spring-test 不在测试类路径，用动态代理实现）。 */
    private static ServletRequestAttributes servletAttributes(AtomicInteger status) {
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                AttachmentControllerMqBranchTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    if ("getAttribute".equals(method.getName())
                            && args != null && args.length == 1
                            && "authSubject".equals(args[0])) {
                        return "STAFF001";
                    }
                    return defaultValue(method.getReturnType());
                });
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                AttachmentControllerMqBranchTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    if ("setStatus".equals(method.getName()) && args != null && args.length == 1) {
                        status.set((Integer) args[0]);
                        return null;
                    }
                    if ("getStatus".equals(method.getName())) {
                        return status.get();
                    }
                    return defaultValue(method.getReturnType());
                });
        return new ServletRequestAttributes(request, response);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    /** 发布端桩：记录投递的信封。 */
    private static final class StubEnvelopePublisher implements EnvelopePublisher {

        private final boolean enabled;
        final List<CipherEnvelope> published = new ArrayList<>();
        final List<String> topics = new ArrayList<>();

        StubEnvelopePublisher(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public void publish(String topic, CipherEnvelope envelope) {
            topics.add(topic);
            published.add(envelope);
        }
    }

    /** 加解密端桩：只回放封装时的载荷（生产端封装的密文内容由基础设施层负责）。 */
    private static final class StubEnvelopeCipher implements EnvelopeCipher {

        String sealedPayload;
        String sealedBizType;
        String sealedBizRef;

        @Override
        public CipherEnvelope seal(String bizType, String bizRef, String payloadJson, String traceId) {
            this.sealedBizType = bizType;
            this.sealedBizRef = bizRef;
            this.sealedPayload = payloadJson;
            return new CipherEnvelope("env-1", bizType, bizRef, "v1", "AES-256-GCM",
                    "Y2lwaGVydGV4dA==", traceId, "2026-11-23T09:00:00+09:00");
        }

        @Override
        public String open(CipherEnvelope envelope) {
            return sealedPayload;
        }
    }

    /** 附件仓储桩（绕开 DB，记录登记与转正参数）。 */
    private static final class StubAttachmentRepository extends AttachmentRepository {

        AttachmentRow pending;
        AttachmentRow registered;
        int inserts;
        int promotes;
        String lastBizType;
        String lastBizId;

        StubAttachmentRepository() {
            super(null);
        }

        @Override
        public long insert(String bizType, String bizId, String relativePath, String sha256,
                           long sizeBytes, String uploadedBy, String traceId) {
            inserts++;
            lastBizType = bizType;
            lastBizId = bizId;
            registered = new AttachmentRow(inserts, bizType, bizId, relativePath, sha256,
                    sizeBytes, uploadedBy, traceId);
            return inserts;
        }

        @Override
        public Optional<AttachmentRow> findPending(String objectKey) {
            return pending == null ? Optional.empty() : Optional.of(pending);
        }

        @Override
        public Optional<AttachmentRow> findRegisteredByRelativePath(String relativePath) {
            return registered == null ? Optional.empty() : Optional.of(registered);
        }

        @Override
        public boolean promotePending(String objectKey, String bizType, String bizId) {
            promotes++;
            lastBizType = bizType;
            lastBizId = bizId;
            if (pending == null) {
                return false;
            }
            registered = new AttachmentRow(pending.attachmentId(), bizType, bizId,
                    pending.relativePath(), pending.sha256(), pending.sizeBytes(),
                    pending.uploadedBy(), pending.traceId());
            pending = null;
            return true;
        }
    }
}
