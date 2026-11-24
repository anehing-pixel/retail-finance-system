package com.company.retail.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双主体 JWT 密钥启动门禁（R11 P0-8）：缺失 / 弱默认 / 两把相同 → 启动失败。
 */
class RfSecurityConfigSecretTest {

    private static final String STRONG_STAFF = "staff-secret-0123456789abcdef0123456789abcdef";
    private static final String STRONG_CUSTOMER = "customer-secret-0123456789abcdef01234567";
    private static final String DEV_STAFF =
            "dev-only-retail-staff-secret-change-me-in-prod-0123456789";
    private static final String DEV_CUSTOMER =
            "dev-only-retail-customer-secret-change-me-in-prod-01234";
    private static final String[] PREFIXES = {"/api/customer-self/**"};

    private static StandardEnvironment env(String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    void missingStaffSecretFailsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new RfSecurityConfig(null, STRONG_CUSTOMER, PREFIXES, env()));
        assertTrue(ex.getMessage().contains("retail.auth.secret"));
    }

    @Test
    void missingCustomerSecretFailsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new RfSecurityConfig(STRONG_STAFF, null, PREFIXES, env()));
        assertTrue(ex.getMessage().contains("retail.customer-auth.secret"));
    }

    @Test
    void devDefaultsAreRejectedWithoutDevProfile() {
        assertThrows(IllegalStateException.class,
                () -> new RfSecurityConfig(DEV_STAFF, DEV_CUSTOMER, PREFIXES, env()));
    }

    @Test
    void devDefaultsAreAcceptedWithDevProfile() {
        assertDoesNotThrow(() ->
                new RfSecurityConfig(DEV_STAFF, DEV_CUSTOMER, PREFIXES, env("dev")));
    }

    @Test
    void identicalSecretsAreRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new RfSecurityConfig(STRONG_STAFF, STRONG_STAFF, PREFIXES, env()));
        assertTrue(ex.getMessage().contains("retail.auth.secret"));
        assertTrue(ex.getMessage().contains("retail.customer-auth.secret"));
        assertFalse(ex.getMessage().contains(STRONG_STAFF));
    }

    @Test
    void strongSecretsPass() {
        assertDoesNotThrow(() ->
                new RfSecurityConfig(STRONG_STAFF, STRONG_CUSTOMER, PREFIXES, env()));
    }
}
