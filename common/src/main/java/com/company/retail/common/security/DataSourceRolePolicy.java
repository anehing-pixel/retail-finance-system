package com.company.retail.common.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 运行期连库角色门禁（R11 P0-1 / G-DB-2 / G-DB-6）。
 *
 * <p>业务连接池只允许三个受限角色：{@code retail_app_rw} / {@code retail_pii_rw} /
 * {@code retail_dmz_rw}；{@code postgres} 等超管账号一律拒绝启动。口令不得为
 * {@code postgres}，非 dev profile 下拒绝 V1 的 {@code *_dev} 开发口令。
 * 迁移账号（Flyway）不走本策略——它只用于迁移期，由 Spring Flyway 单独配置。</p>
 */
public final class DataSourceRolePolicy {

    /** 运行期允许的三个受限角色（G-DB-2）。 */
    public static final Set<String> RUNTIME_ROLES =
            Set.of("retail_app_rw", "retail_pii_rw", "retail_dmz_rw");

    /** 数据库口令最低长度（字节）。 */
    public static final int MIN_PASSWORD_BYTES = 12;

    /** 非 dev profile 拒绝的开发期弱口令标记。 */
    private static final List<String> WEAK_MARKERS =
            List.of("_dev", "dev-only", "change-me", "changeme");

    private DataSourceRolePolicy() {
    }

    /** 校验运行期用户名：必须是三个受限角色之一（大小写敏感，与 PostgreSQL 角色名一致）。 */
    public static String requireUsername(String configKey, String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalStateException(
                    "数据库账号未配置：" + configKey + "（运行期必须使用受限角色，不允许兜底）");
        }
        if ("postgres".equalsIgnoreCase(username)) {
            throw new IllegalStateException(
                    "禁止以超级用户 postgres 连接运行期数据库：" + configKey
                            + "（G-DB-6：超管仅限迁移与运维）");
        }
        if (!RUNTIME_ROLES.contains(username)) {
            throw new IllegalStateException(
                    "非法的运行期数据库角色：" + configKey + "=" + username
                            + "（只允许 " + RUNTIME_ROLES + "）");
        }
        return username;
    }

    /** 校验运行期口令：非空、≥12 字节、不得为 postgres；非 dev profile 拒绝 {@code *_dev} 等弱口令。 */
    public static String requirePassword(String configKey, String password, boolean devMode) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "数据库口令未注入：" + configKey + "（缺失即拒绝启动，不提供兜底值）");
        }
        if ("postgres".equalsIgnoreCase(password)) {
            throw new IllegalStateException(
                    "数据库口令不得为 postgres：" + configKey + "（G-DB-1 / G-DB-6）");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length < MIN_PASSWORD_BYTES) {
            throw new IllegalStateException(
                    "数据库口令强度不足：" + configKey + " 至少需要 " + MIN_PASSWORD_BYTES + " 字节");
        }
        if (!devMode) {
            String lower = password.toLowerCase(Locale.ROOT);
            for (String marker : WEAK_MARKERS) {
                if (lower.contains(marker)) {
                    throw new IllegalStateException(
                            "数据库口令疑似开发期弱口令：" + configKey
                                    + "（生产环境拒绝；本地请显式激活 dev profile）");
                }
            }
        }
        return password;
    }
}
