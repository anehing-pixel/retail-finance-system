package com.company.retail.common.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 密钥强度门禁（R11 P0-8）：启动期统一校验必须由外部注入的密钥。
 *
 * <p>规则：缺失 / 空串 / 少于 32 字节 → 直接拒绝启动；非 dev profile 下命中
 * {@code dev-only}、{@code change-me} 等开发期标记也拒绝。错误消息只报配置键名，
 * <b>不输出密钥内容</b>。本地开发由 {@code scripts/dev-secrets.sh} 显式激活
 * {@code dev} profile 并注入 dev 值——生产不 source 该脚本，缺失注入即启动失败。</p>
 */
public final class SecretPolicy {

    /** HS256 / HMAC-SHA256 的最低密钥长度（字节）。 */
    public static final int MIN_SECRET_BYTES = 32;

    /** 仅 dev profile 允许出现的开发期标记；生产环境命中即拒绝。 */
    private static final List<String> DEV_MARKERS =
            List.of("dev-only", "change-me", "changeme", "in-prod");

    private SecretPolicy() {
    }

    /**
     * 校验并返回密钥原值（不 trim，密钥按字面使用）。
     *
     * @param configKey 配置键名（含环境变量名，用于报错定位）
     * @param value     注入的密钥
     * @param devMode   是否显式激活 dev profile（仅本地）
     */
    public static String requireStrong(String configKey, String value, boolean devMode) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "密钥未注入：" + configKey + "（缺失即拒绝启动，不提供兜底值）");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "密钥强度不足：" + configKey + " 至少需要 " + MIN_SECRET_BYTES + " 字节");
        }
        if (!devMode) {
            String lower = value.toLowerCase(Locale.ROOT);
            for (String marker : DEV_MARKERS) {
                if (lower.contains(marker)) {
                    throw new IllegalStateException(
                            "密钥疑似开发期弱默认值：" + configKey
                                    + "（生产环境拒绝；本地请显式激活 dev profile）");
                }
            }
        }
        return value;
    }

    /** 两把密钥不得复用（R00 §6：三把密钥互不相同）。 */
    public static void requireDistinct(String keyA, String valueA, String keyB, String valueB) {
        if (valueA != null && valueA.equals(valueB)) {
            throw new IllegalStateException(
                    "密钥不得复用：" + keyA + " 与 " + keyB + " 必须互不相同");
        }
    }

    /** dev profile 判定（大小写不敏感）；null / 空数组视为非 dev。 */
    public static boolean devMode(String[] activeProfiles) {
        if (activeProfiles == null) {
            return false;
        }
        for (String profile : activeProfiles) {
            if ("dev".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }
}
