package com.company.retail.externalapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DMZ 三关开关（W9-B，R07 §5.2）：生产行为默认开，测试可单独关。
 *
 * <ul>
 *   <li>{@code registry-mode}：partner 档案（状态/scope/IP 白名单）来源——
 *       {@code CONFIG}（默认，W2 dev 口径，配置即真相源）/ {@code DB}
 *       （{@code integration.partners} 表，生产真相源）；</li>
 *   <li>{@code nonce-enabled}：nonce 写 {@code integration.nonces} 防重放；</li>
 *   <li>{@code nonce-retention-seconds}：nonce 留存期（默认 600s = 2×时间戳容差），
 *       惰性清理——过期 nonce 物理删除后同名可再用（但时间戳早已过期，无重放风险）；</li>
 *   <li>{@code scope-enabled}：partner scope 校验，超出 scope → 403 RF_PTNR_003；</li>
 *   <li>{@code ip-allowlist-enabled}：全局 IP 白名单开关（默认 false）；
 *       partner 级名单为空表示该 partner 不限 IP；</li>
 *   <li>{@code trust-forwarded-for}：是否信任 {@code X-Forwarded-For} 首跳判定客户端 IP。
 *       默认 false——直连场景可伪造 XFF 绕过白名单；仅在 DMZ 确有可信 LB 前置时开启。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "retail.dmz")
public class DmzProperties {

    /** partner 档案来源：CONFIG（配置）/ DB（integration.partners 表）。 */
    private String registryMode = "CONFIG";

    private boolean nonceEnabled = true;

    private long nonceRetentionSeconds = 600;

    private boolean scopeEnabled = true;

    private boolean ipAllowlistEnabled = false;

    private boolean trustForwardedFor = false;

    public String getRegistryMode() {
        return registryMode;
    }

    public void setRegistryMode(String registryMode) {
        this.registryMode = registryMode;
    }

    public boolean isNonceEnabled() {
        return nonceEnabled;
    }

    public void setNonceEnabled(boolean nonceEnabled) {
        this.nonceEnabled = nonceEnabled;
    }

    public long getNonceRetentionSeconds() {
        return nonceRetentionSeconds;
    }

    public void setNonceRetentionSeconds(long nonceRetentionSeconds) {
        this.nonceRetentionSeconds = nonceRetentionSeconds;
    }

    public boolean isScopeEnabled() {
        return scopeEnabled;
    }

    public void setScopeEnabled(boolean scopeEnabled) {
        this.scopeEnabled = scopeEnabled;
    }

    public boolean isIpAllowlistEnabled() {
        return ipAllowlistEnabled;
    }

    public void setIpAllowlistEnabled(boolean ipAllowlistEnabled) {
        this.ipAllowlistEnabled = ipAllowlistEnabled;
    }

    public boolean isTrustForwardedFor() {
        return trustForwardedFor;
    }

    public void setTrustForwardedFor(boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }
}
