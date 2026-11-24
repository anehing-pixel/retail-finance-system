package com.company.retail.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行期连库角色门禁（R11 P0-1 / G-DB-2 / G-DB-6）：
 * 只允许三个受限业务角色，超管与未知角色一律启动失败；口令不得为 postgres，
 * 非 dev profile 拒绝 {@code *_dev} 弱口令。
 */
class DataSourceRolePolicyTest {

    private static final String STRONG = "db-strong-password-0123456789";

    @Test
    void acceptsThreeRuntimeRoles() {
        for (String role : new String[]{"retail_app_rw", "retail_pii_rw", "retail_dmz_rw"}) {
            assertEquals(role, DataSourceRolePolicy.requireUsername("spring.datasource.username", role));
        }
    }

    @Test
    void rejectsPostgresSuperuser() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DataSourceRolePolicy.requireUsername("spring.datasource.username", "postgres"));
        assertTrue(ex.getMessage().contains("spring.datasource.username"));
        assertTrue(ex.getMessage().contains("postgres"));
    }

    @Test
    void rejectsUnknownOrNonRuntimeRole() {
        assertThrows(IllegalStateException.class,
                () -> DataSourceRolePolicy.requireUsername("spring.datasource.username", "retail_audit_ins"));
        assertThrows(IllegalStateException.class,
                () -> DataSourceRolePolicy.requireUsername("spring.datasource.username", "admin"));
        // 角色名大小写敏感：大写不是合法角色
        assertThrows(IllegalStateException.class,
                () -> DataSourceRolePolicy.requireUsername("spring.datasource.username", "RETAIL_APP_RW"));
    }

    @Test
    void rejectsBlankUsername() {
        assertThrows(IllegalStateException.class,
                () -> DataSourceRolePolicy.requireUsername("spring.datasource.username", null));
        assertThrows(IllegalStateException.class,
                () -> DataSourceRolePolicy.requireUsername("spring.datasource.username", "  "));
    }

    @Test
    void rejectsBlankOrShortPassword() {
        assertThrows(IllegalStateException.class,
                () -> DataSourceRolePolicy.requirePassword("spring.datasource.password", null, false));
        assertThrows(IllegalStateException.class,
                () -> DataSourceRolePolicy.requirePassword("spring.datasource.password", "", false));
        assertThrows(IllegalStateException.class,
                () -> DataSourceRolePolicy.requirePassword("spring.datasource.password", "short", false));
    }

    @Test
    void rejectsPostgresPasswordAlways() {
        assertThrows(IllegalStateException.class,
                () -> DataSourceRolePolicy.requirePassword("spring.datasource.password", "postgres", false));
        assertThrows(IllegalStateException.class,
                () -> DataSourceRolePolicy.requirePassword("spring.datasource.password", "POSTGRES", true));
    }

    @Test
    void devPasswordIsRejectedOutsideDevProfile() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DataSourceRolePolicy.requirePassword(
                        "spring.datasource.password", "retail_app_rw_dev", false));
        assertTrue(ex.getMessage().contains("spring.datasource.password"));
        assertFalse(ex.getMessage().contains("retail_app_rw_dev"));
    }

    @Test
    void devPasswordIsAllowedWithDevProfile() {
        assertEquals("retail_app_rw_dev", DataSourceRolePolicy.requirePassword(
                "spring.datasource.password", "retail_app_rw_dev", true));
    }

    @Test
    void strongPasswordPassesInAnyProfile() {
        assertDoesNotThrow(() ->
                DataSourceRolePolicy.requirePassword("spring.datasource.password", STRONG, false));
        assertDoesNotThrow(() ->
                DataSourceRolePolicy.requirePassword("spring.datasource.password", STRONG, true));
    }
}
