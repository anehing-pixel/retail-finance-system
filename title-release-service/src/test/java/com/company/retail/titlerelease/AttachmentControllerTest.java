package com.company.retail.titlerelease;

import com.company.platform.exception.BusinessException;
import com.company.platform.security.RequirePermission;
import com.company.platform.security.RequireSubject;
import com.company.platform.security.SubjectType;
import com.company.retail.domain.model.Permission;
import com.company.retail.titlerelease.persistence.JdbcAttachmentRepository;
import com.company.retail.titlerelease.storage.LocalStorageAttachmentStorage;
import com.company.retail.titlerelease.web.AttachmentController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附件端点（W9-C）：预签名直传、SHA-256 对拍登记、受控下载（员工权限 + 权限点守卫）。
 */
class AttachmentControllerTest {

    @TempDir
    Path tempDir;

    private LocalStorageAttachmentStorage storage;
    private StubAttachmentRepository repository;
    private AttachmentController controller;

    @BeforeEach
    void setUp() {
        storage = new LocalStorageAttachmentStorage(tempDir.toString(), "test-token-secret", 1024);
        repository = new StubAttachmentRepository();
        controller = new AttachmentController(
                provider(storage), provider(storage), repository);
        // 模拟验签中间件放入的请求身份（B-9：actor 取验签身份）
        StubRequestAttributes attrs = new StubRequestAttributes();
        attrs.setAttribute("authSubject", "STAFF001", RequestAttributes.SCOPE_REQUEST);
        attrs.setAttribute("authSubjectType", "EMPLOYEE", RequestAttributes.SCOPE_REQUEST);
        RequestContextHolder.setRequestAttributes(attrs);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ---------- 预签名签发 ----------

    @Test
    void presign_issueGrant_roundTripsThroughLocalUpload() {
        String key = LocalStorageAttachmentStorage.buildObjectKey(
                "release-order", "3", "SEAL_CERTIFICATE", ".txt");
        var result = controller.presign(new AttachmentController.PresignRequest(
                key, "text/plain", 5, 60));
        String url = String.valueOf(result.getData().get("uploadUrl"));
        assertTrue(url.contains("/api/attachments/local-upload"));

        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        var response = controller.upload(key, "text/plain", 5,
                Long.parseLong(param(url, "expiresAt")), param(url, "signature"), body);
        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(body, storage.get(key));
    }

    @Test
    void presign_declaredSizeOverLimit_rejected() {
        assertThrows(BusinessException.class, () -> controller.presign(
                new AttachmentController.PresignRequest("k.txt", "text/plain", 4096, 60)));
    }

    // ---------- 预签名直传 ----------

    @Test
    void upload_validSignature_writesAndReturnsSha256() {
        String key = LocalStorageAttachmentStorage.buildObjectKey(
                "release-order", "1", "RELEASE_CONSENT", ".txt");
        byte[] body = "manual-uploaded-doc".getBytes(StandardCharsets.UTF_8);
        var presigned = storage.presignUpload(key, "text/plain", body.length, Duration.ofSeconds(60));
        long expiresAt = Long.parseLong(param(presigned.uploadUrl(), "expiresAt"));
        String signature = param(presigned.uploadUrl(), "signature");

        var response = controller.upload(key, "text/plain", body.length, expiresAt,
                signature, body);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(storage.head(key).sha256(), response.getBody().get("sha256"));
    }

    @Test
    void upload_badSignature_forbidden() {
        byte[] body = "doc".getBytes(StandardCharsets.UTF_8);
        var response = controller.upload("k.txt", "text/plain", body.length,
                System.currentTimeMillis() + 60_000, "tampered", body);
        assertEquals(403, response.getStatusCode().value());
        // 无效授权不得落盘
        assertTrue(storage.get("k.txt") == null);
    }

    @Test
    void upload_bodyLengthMismatch_rejected() {
        String key = "k.txt";
        var presigned = storage.presignUpload(key, "text/plain", 10, Duration.ofSeconds(60));
        long expiresAt = Long.parseLong(param(presigned.uploadUrl(), "expiresAt"));
        String signature = param(presigned.uploadUrl(), "signature");

        var response = controller.upload(key, "text/plain", 10, expiresAt, signature,
                "short".getBytes(StandardCharsets.UTF_8));
        assertEquals(400, response.getStatusCode().value());
    }

    // ---------- 登记回读对拍 ----------

    @Test
    void register_sha256Mismatch_rejected() {
        String key = "k.txt";
        storage.put(key, "real-content".getBytes(StandardCharsets.UTF_8));

        assertThrows(BusinessException.class, () -> controller.register(
                new AttachmentController.RegisterRequest(key,
                        "deadbeef".repeat(8), 12, "release_order", "1")));
        assertEquals(0, repository.inserted);
    }

    @Test
    void register_sha256Matches_insertsRow() {
        String key = "k.txt";
        byte[] content = "release-doc".getBytes(StandardCharsets.UTF_8);
        String sha256 = storage.put(key, content);

        var result = controller.register(new AttachmentController.RegisterRequest(
                key, sha256, content.length, "release_order", "7"));

        assertEquals(1L, ((Number) result.getData().get("attachmentId")).longValue());
        assertEquals(1, repository.inserted);
        assertEquals("release_order", repository.lastBizType);
        assertEquals("7", repository.lastBizId);
    }

    // ---------- 受控下载 ----------

    @Test
    void download_byAttachmentId_streamsContentWithHashHeader() {
        String key = "release-order/2026/11/d.txt";
        byte[] content = "downloaded-doc".getBytes(StandardCharsets.UTF_8);
        String sha256 = storage.put(key, content);
        repository.row = new JdbcAttachmentRepository.AttachmentRow(
                5L, "release_order", "9", key, sha256, content.length, "STAFF001", "t1");

        var response = controller.download(5L);

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(content, response.getBody());
        assertEquals(sha256, response.getHeaders().getFirst("X-Attachment-Sha256"));
        // PII 注意：文件名只用对象键/附件号，不回显原始文件名
        assertTrue(response.getHeaders().getFirst("Content-Disposition")
                .contains("attachment-5"));
    }

    @Test
    void download_unknownAttachment_throws() {
        assertThrows(BusinessException.class, () -> controller.download(999));
    }

    @Test
    void download_missingFile_notFound() {
        repository.row = new JdbcAttachmentRepository.AttachmentRow(
                5L, "release_order", "9", "gone.txt", "a".repeat(64), 1, "STAFF001", "t1");
        assertEquals(404, controller.download(5L).getStatusCode().value());
    }

    @Test
    void downloadSigned_validSignature_streamsContent() {
        String key = "release-order/2026/11/s.txt";
        byte[] content = "signed-download".getBytes(StandardCharsets.UTF_8);
        storage.put(key, content);
        String url = storage.presignGet(key, Duration.ofSeconds(60));
        long expiresAt = Long.parseLong(param(url, "expiresAt"));
        String signature = param(url, "signature");

        var response = controller.downloadSigned(key, expiresAt, signature);
        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(content, response.getBody());

        assertEquals(403, controller.downloadSigned(key, expiresAt, "bogus")
                .getStatusCode().value());
    }

    // ---------- 权限守卫（平台注解静态断言：受控下载必须员工 + 权限点） ----------

    @Test
    void endpoints_guardedByEmployeeAuthAndReleasePermission() throws NoSuchMethodException {
        RequireSubject subject = AttachmentController.class.getAnnotation(RequireSubject.class);
        assertEquals(1, subject.value().length);
        assertEquals(SubjectType.EMPLOYEE, subject.value()[0]);
        for (Method m : new Method[]{method("download", long.class),
                method("register", AttachmentController.RegisterRequest.class),
                method("upload", String.class, String.class, long.class, long.class,
                        String.class, byte[].class)}) {
            RequirePermission p = m.getAnnotation(RequirePermission.class);
            assertTrue(p != null && p.value().length == 1
                    && Permission.RELEASE_DOCUMENT_ISSUE.equals(p.value()[0]),
                    "端点缺少权限守卫：" + m.getName());
        }
    }

    private static Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return AttachmentController.class.getMethod(name, params);
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

    /** 附件仓储桩（记录登记参数，绕开 DB）。 */
    private static final class StubAttachmentRepository extends JdbcAttachmentRepository {
        JdbcAttachmentRepository.AttachmentRow row;
        int inserted;
        String lastBizType;
        String lastBizId;

        StubAttachmentRepository() {
            super(null);
        }

        @Override
        public long insert(String bizType, String bizId, String relativePath, String sha256,
                           long sizeBytes, String uploadedBy, String traceId) {
            inserted++;
            lastBizType = bizType;
            lastBizId = bizId;
            return inserted;
        }

        @Override
        public Optional<JdbcAttachmentRepository.AttachmentRow> findById(long attachmentId) {
            return row == null ? Optional.empty() : Optional.of(row);
        }
    }

    /** 最小 RequestAttributes 桩（spring-test 不在测试类路径）。 */
    private static final class StubRequestAttributes implements RequestAttributes {
        final Map<String, Object> requestScope = new HashMap<>();

        @Override
        public Object getAttribute(String name, int scope) {
            return scope == SCOPE_REQUEST ? requestScope.get(name) : null;
        }

        @Override
        public void setAttribute(String name, Object value, int scope) {
            if (scope == SCOPE_REQUEST) {
                requestScope.put(name, value);
            }
        }

        @Override
        public void removeAttribute(String name, int scope) {
            if (scope == SCOPE_REQUEST) {
                requestScope.remove(name);
            }
        }

        @Override
        public String[] getAttributeNames(int scope) {
            return scope == SCOPE_REQUEST ? requestScope.keySet().toArray(String[]::new) : new String[0];
        }

        @Override
        public void registerDestructionCallback(String name, Runnable callback, int scope) {
        }

        @Override
        public Object resolveReference(String key) {
            return null;
        }

        @Override
        public String getSessionId() {
            return null;
        }

        @Override
        public Object getSessionMutex() {
            return this;
        }
    }
}
