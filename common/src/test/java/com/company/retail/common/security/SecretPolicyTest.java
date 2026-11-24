package com.company.retail.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密钥强度门禁（R11 P0-8）：缺失 / 过短 / 生产环境弱默认值一律启动失败，
 * 错误消息只报配置键，不泄露密钥内容。
 */
class SecretPolicyTest {

    private static final String STRONG = "strong-secret-0123456789abcdef0123456789";
    private static final String DEV_DEFAULT =
            "dev-only-retail-staff-secret-change-me-in-prod-0123456789";

    @Test
    void missingSecretFailsClosed() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SecretPolicy.requireStrong("retail.auth.secret", null, false));
        assertTrue(ex.getMessage().contains("retail.auth.secret"));
        assertFalse(ex.getMessage().contains(DEV_DEFAULT));
    }

    @Test
    void blankSecretFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> SecretPolicy.requireStrong("retail.auth.secret", "", false));
        assertThrows(IllegalStateException.class,
                () -> SecretPolicy.requireStrong("retail.auth.secret", "   ", false));
    }

    @Test
    void shortSecretIsRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SecretPolicy.requireStrong("retail.pii.hmac-key", "short-secret", false));
        assertTrue(ex.getMessage().contains("retail.pii.hmac-key"));
        assertFalse(ex.getMessage().contains("short-secret"));
    }

    @Test
    void devDefaultIsRejectedOutsideDevProfile() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SecretPolicy.requireStrong("retail.auth.secret", DEV_DEFAULT, false));
        assertTrue(ex.getMessage().contains("retail.auth.secret"));
        assertFalse(ex.getMessage().contains(DEV_DEFAULT));
    }

    @Test
    void devDefaultIsAllowedOnlyWithDevProfile() {
        assertEquals(DEV_DEFAULT, SecretPolicy.requireStrong("retail.auth.secret", DEV_DEFAULT, true));
    }

    @Test
    void devProfileStillRequiresMinimumLength() {
        assertThrows(IllegalStateException.class,
                () -> SecretPolicy.requireStrong("retail.auth.secret", "dev-only", true));
    }

    @Test
    void strongSecretPassesInAnyProfile() {
        assertEquals(STRONG, SecretPolicy.requireStrong("retail.auth.secret", STRONG, false));
        assertEquals(STRONG, SecretPolicy.requireStrong("retail.auth.secret", STRONG, true));
    }

    @Test
    void equalSecretsAreRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SecretPolicy.requireDistinct(
                        "retail.auth.secret", STRONG, "retail.customer-auth.secret", STRONG));
        assertTrue(ex.getMessage().contains("retail.auth.secret"));
        assertTrue(ex.getMessage().contains("retail.customer-auth.secret"));
        assertFalse(ex.getMessage().contains(STRONG));
    }

    @Test
    void devModeRecognizesDevProfile() {
        assertTrue(SecretPolicy.devMode(new String[]{"prod", "dev"}));
        assertTrue(SecretPolicy.devMode(new String[]{"DEV"}));
        assertFalse(SecretPolicy.devMode(new String[]{"prod"}));
        assertFalse(SecretPolicy.devMode(new String[0]));
        assertFalse(SecretPolicy.devMode(null));
    }
}
