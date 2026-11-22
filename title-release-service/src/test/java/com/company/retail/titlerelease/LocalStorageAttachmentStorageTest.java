package com.company.retail.titlerelease;

import com.company.retail.titlerelease.storage.LocalStorageAttachmentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地附件存储（ADR-RF-008）：写入/SHA-256/大小超限拒绝/授权签名/防目录穿越/对象键无 PII。
 */
class LocalStorageAttachmentStorageTest {

    @TempDir
    Path tempDir;

    private LocalStorageAttachmentStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalStorageAttachmentStorage(tempDir.toString(),
                "test-token-secret", 1024);
    }

    @Test
    void put_writesFileAndReturnsSha256() throws Exception {
        byte[] content = "release-document-content".getBytes(StandardCharsets.UTF_8);
        String sha256 = storage.put("release-order/2026/11/abc-1.txt", content);

        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
        assertEquals(expected, sha256);
        // DB 存相对路径，文件本体确实落在 base-dir 下
        Path file = tempDir.resolve("release-order/2026/11/abc-1.txt");
        assertTrue(Files.exists(file));
        assertArrayEquals(content, Files.readAllBytes(file));
        // 回读一致
        assertArrayEquals(content, storage.get("release-order/2026/11/abc-1.txt"));
    }

    @Test
    void put_sizeOverLimit_rejected() {
        byte[] big = new byte[1025];   // limit = 1024
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("release-order/2026/11/big.txt", big));
        // 超限文件不得落盘
        assertFalse(Files.exists(tempDir.resolve("release-order/2026/11/big.txt")));
    }

    @Test
    void presignUpload_signatureBindsKeyAndLength() {
        String key = "release-order/2026/11/k.txt";
        var presigned = storage.presignUpload(key, "text/plain", 100, Duration.ofSeconds(60));
        assertEquals(key, presigned.objectKey());

        long expiresAt = Long.parseLong(extractParam(presigned.uploadUrl(), "expiresAt"));
        String signature = extractParam(presigned.uploadUrl(), "signature");
        // 同 key 同长度同类型同时效 → 有效
        assertTrue(storage.verifyUploadSignature(key, "text/plain", 100, expiresAt, signature));
        // 授权 A 不能传 B（换 key）→ 无效
        assertFalse(storage.verifyUploadSignature("release-order/2026/11/other.txt",
                "text/plain", 100, expiresAt, signature));
        // 授权 A 不能传更长内容（换 contentLength）→ 无效
        assertFalse(storage.verifyUploadSignature(key, "text/plain", 2000, expiresAt, signature));
        // 授权 A 不能传别的类型 → 无效
        assertFalse(storage.verifyUploadSignature(key, "application/pdf", 100, expiresAt, signature));
        // 过期 → 无效
        assertFalse(storage.verifyUploadSignature(key, "text/plain", 100,
                System.currentTimeMillis() - 1, signature));
    }

    @Test
    void presignGet_expiredOrWrongSignature_rejected() {
        storage.put("k.txt", "content".getBytes(StandardCharsets.UTF_8));
        String url = storage.presignGet("k.txt", Duration.ofSeconds(60));
        long expiresAt = Long.parseLong(extractParam(url, "expiresAt"));
        String signature = extractParam(url, "signature");
        assertTrue(storage.verifyGetSignature("k.txt", expiresAt, signature));
        // 过期
        assertFalse(storage.verifyGetSignature("k.txt", System.currentTimeMillis() - 1, signature));
        // 换 key / 篡改签名
        assertFalse(storage.verifyGetSignature("other.txt", expiresAt, signature));
        assertFalse(storage.verifyGetSignature("k.txt", expiresAt, "bogus-signature"));
    }

    @Test
    void get_missingObject_returnsNull_headReturnsNull() {
        assertNull(storage.get("release-order/2026/11/nope.txt"));
        assertNull(storage.head("release-order/2026/11/nope.txt"));
        assertNull(storage.presignGet("release-order/2026/11/nope.txt", Duration.ofSeconds(30)));
    }

    @Test
    void pathTraversal_objectKey_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("../escape.txt", "x".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> storage.get("sub/../../escape.txt"));
    }

    @Test
    void buildObjectKey_noOriginalFilename_noPii() {
        String key = LocalStorageAttachmentStorage.buildObjectKey(
                "release-order", "42", "RELEASE_CONSENT", ".txt");
        // bizType 分目录 + 年月 + uuid-bizId-docType：不含原始文件名/客户信息
        assertTrue(key.startsWith("release-order/"));
        assertTrue(key.contains("-42-release_consent.txt"));
        // 两次生成键不同（UUID，不冲突不覆盖）
        assertNotEquals(key, LocalStorageAttachmentStorage.buildObjectKey(
                "release-order", "42", "RELEASE_CONSENT", ".txt"));
    }

    @Test
    void providerName_isLocal() {
        assertEquals("local", storage.providerName());
        assertEquals("/api/attachments/local-upload", storage.uploadEndpointPath());
        assertEquals("/api/attachments/local-download", storage.downloadEndpointPath());
        assertNotNull(storage);
    }

    private static String extractParam(String url, String name) {
        for (String part : url.substring(url.indexOf('?') + 1).split("&")) {
            if (part.startsWith(name + "=")) {
                return part.substring(name.length() + 1);
            }
        }
        throw new IllegalStateException("参数缺失：" + name);
    }
}
