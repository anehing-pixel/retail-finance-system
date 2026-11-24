package com.company.retail.titlerelease.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附件签名密钥启动门禁（R11 P0-8）：缺失 / 过短 / 生产环境弱默认值一律拒绝启动。
 */
class LocalStorageAttachmentStorageSecretTest {

    private static final String STRONG = "attachment-token-secret-0123456789abcdef";
    private static final String DEV_DEFAULT = "dev-only-local-attachment-token-0123456789";

    @TempDir
    Path tempDir;

    private static StandardEnvironment env(String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    private LocalStorageAttachmentStorage build(String secret, StandardEnvironment env) {
        return new LocalStorageAttachmentStorage(tempDir.toString(), secret, 1024, env);
    }

    @Test
    void missingSecretFailsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> build(null, env()));
        assertTrue(ex.getMessage().contains("retail.attachments.token-secret"));
    }

    @Test
    void shortSecretFailsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> build("short-secret", env()));
        assertTrue(ex.getMessage().contains("retail.attachments.token-secret"));
        assertFalse(ex.getMessage().contains("short-secret"));
    }

    @Test
    void devDefaultIsRejectedWithoutDevProfile() {
        assertThrows(IllegalStateException.class, () -> build(DEV_DEFAULT, env()));
    }

    @Test
    void devDefaultIsAcceptedWithDevProfile() {
        assertDoesNotThrow(() -> build(DEV_DEFAULT, env("dev")));
    }

    @Test
    void strongSecretPasses() {
        assertDoesNotThrow(() -> build(STRONG, env()));
    }
}
