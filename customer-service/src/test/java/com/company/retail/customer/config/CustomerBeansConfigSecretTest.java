package com.company.retail.customer.config;

import com.company.retail.customer.infra.PiiCryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PII 密钥启动门禁（R11 P0-8）：data-key / hmac-key 缺失、过短、复用一律拒绝启动。
 */
class CustomerBeansConfigSecretTest {

    private static final String DATA_KEY = "pii-data-key-0123456789abcdef0123456789";
    private static final String HMAC_KEY = "pii-hmac-key-0123456789abcdef0123456789";
    private static final String DEV_DATA_KEY =
            "dev-only-retail-pii-data-key-change-me-in-prod-012345";
    private static final String DEV_HMAC_KEY =
            "dev-only-retail-pii-hmac-key-change-me-in-prod-01234";

    private static StandardEnvironment env(String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    private static PiiCryptoService build(String dataKey, String hmacKey, StandardEnvironment env) {
        return new CustomerBeansConfig().piiCryptoService(dataKey, hmacKey, "v1", env);
    }

    @Test
    void missingDataKeyFailsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> build(null, HMAC_KEY, env()));
        assertTrue(ex.getMessage().contains("retail.pii.data-key"));
        assertFalse(ex.getMessage().contains(HMAC_KEY));
    }

    @Test
    void missingHmacKeyFailsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> build(DATA_KEY, "", env()));
        assertTrue(ex.getMessage().contains("retail.pii.hmac-key"));
    }

    @Test
    void shortKeyIsRejected() {
        assertThrows(IllegalStateException.class, () -> build("short-data-key", HMAC_KEY, env()));
        assertThrows(IllegalStateException.class, () -> build(DATA_KEY, "short-hmac-key", env()));
    }

    @Test
    void reusedKeyIsRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> build(DATA_KEY, DATA_KEY, env()));
        assertTrue(ex.getMessage().contains("retail.pii.data-key"));
        assertTrue(ex.getMessage().contains("retail.pii.hmac-key"));
        assertFalse(ex.getMessage().contains(DATA_KEY));
    }

    @Test
    void devDefaultsAreRejectedWithoutDevProfile() {
        assertThrows(IllegalStateException.class,
                () -> build(DEV_DATA_KEY, DEV_HMAC_KEY, env()));
    }

    @Test
    void devDefaultsAreAcceptedWithDevProfile() {
        PiiCryptoService crypto = build(DEV_DATA_KEY, DEV_HMAC_KEY, env("dev"));
        byte[] cipher = crypto.encryptField("山田太郎");
        assertEquals("山田太郎", crypto.decryptField(cipher));
    }

    @Test
    void strongKeysPassAndRoundTrip() {
        PiiCryptoService crypto = assertDoesNotThrow(() -> build(DATA_KEY, HMAC_KEY, env()));
        assertNotNull(crypto);
        assertEquals(64, crypto.hmacHash("090-1234-5678").length());
    }
}
