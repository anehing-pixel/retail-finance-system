package com.company.retail.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 数据源启动门禁装配（R11 P0-1）：超管 / 未知角色 / 弱口令在装配期 fail-fast。
 */
class RfDataSourceGuardConfigTest {

    private static final String STRONG = "db-strong-password-0123456789";

    private static StandardEnvironment env(String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    void runtimeRoleWithStrongPasswordPasses() {
        assertDoesNotThrow(() ->
                new RfDataSourceGuardConfig("retail_app_rw", STRONG, env()));
    }

    @Test
    void postgresUsernameFailsStartup() {
        assertThrows(IllegalStateException.class, () ->
                new RfDataSourceGuardConfig("postgres", STRONG, env()));
    }

    @Test
    void unknownRoleFailsStartup() {
        assertThrows(IllegalStateException.class, () ->
                new RfDataSourceGuardConfig("admin", STRONG, env()));
    }

    @Test
    void devPasswordRequiresDevProfile() {
        assertThrows(IllegalStateException.class, () ->
                new RfDataSourceGuardConfig("retail_app_rw", "retail_app_rw_dev", env()));
        assertDoesNotThrow(() ->
                new RfDataSourceGuardConfig("retail_app_rw", "retail_app_rw_dev", env("dev")));
    }

    @Test
    void shortPasswordFailsStartup() {
        assertThrows(IllegalStateException.class, () ->
                new RfDataSourceGuardConfig("retail_app_rw", "short", env()));
    }
}
