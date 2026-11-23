package com.company.retail.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关限流参数（{@code retail.gateway.rate-limit.*}）。
 *
 * <p><b>默认 fail-open</b>：Redis 不可用时放行。限流是<b>保护层</b>而不是安全边界——
 * 真正的正确性防线在各服务内（幂等唯一索引、乐观锁、fail-closed 门禁）。
 * 为了保护层可用性而把主链路打挂，是本末倒置；生产上若要改为 fail-closed 可配置切换。</p>
 */
@ConfigurationProperties(prefix = "retail.gateway.rate-limit")
public class RateLimitProperties {

    /** 限流总开关。 */
    private boolean enabled = true;

    /**
     * 限流实现模式（{@code redis} / {@code memory}）。
     *
     * <p>网关有两套限流实现：{@code redis} = Redis 令牌桶（Lua 原子扣减，分布式准确，<b>默认</b>）；
     * {@code memory} = 进程内令牌桶（零外部依赖，<b>单实例口径</b>，多副本时实际放行量
     * ≈ 单副本额度 × 副本数）。两者<b>互斥装配</b>，同一时刻只注册一个限流过滤器，
     * 不会出现双重限流。口径见 {@code R02 §3.3}。</p>
     */
    private String mode = "redis";

    /** Redis 不可用时是否放行（true = fail-open，默认）。 */
    private boolean failOpen = true;

    /** 限流键维度：{@code auth_or_ip}（优先用 Authorization 头哈希，退化到 IP）/ {@code ip}。 */
    private String keyBy = "auth_or_ip";

    /** Redis 键前缀。 */
    private String keyPrefix = "rf:rl:";

    /**
     * 可信代理网段（逗号分隔的 CIDR）。
     *
     * <p><b>为空 = 不采信任何转发头</b>（默认行为，与上线前一致）：此时客户端 IP 取 TCP 直连地址。
     * 生产在网关前置边缘代理（如 {@code nginx/} 目录的可选形态）时，
     * 填入代理网段后才采信 {@code X-Forwarded-For}——否则所有调用方会被算成同一个 IP。
     * 安全口径：直连来源不在可信网段内时<b>一律忽略</b>转发头，防止「换一个头就重置限流」。</p>
     */
    private String trustedProxyCidrs = "";

    /** 规则列表；命中最具体（pattern 最长）的一条。 */
    private List<Rule> rules = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode == null || mode.isBlank() ? "redis" : mode.trim();
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }

    public String getKeyBy() {
        return keyBy;
    }

    public void setKeyBy(String keyBy) {
        this.keyBy = keyBy;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String getTrustedProxyCidrs() {
        return trustedProxyCidrs;
    }

    public void setTrustedProxyCidrs(String trustedProxyCidrs) {
        this.trustedProxyCidrs = trustedProxyCidrs == null ? "" : trustedProxyCidrs;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules == null ? new ArrayList<>() : rules;
    }

    /**
     * 单条限流规则。
     *
     * @param path          路径模式（Ant 风格，{@code /api/**}）
     * @param replenishRate 补充速率（令牌 / 秒）
     * @param burstCapacity 桶容量（允许的突发量）
     */
    public static class Rule {
        private String path = "/api/**";
        private int replenishRate = 100;
        private int burstCapacity = 200;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public int getReplenishRate() {
            return replenishRate;
        }

        public void setReplenishRate(int replenishRate) {
            this.replenishRate = replenishRate;
        }

        public int getBurstCapacity() {
            return burstCapacity;
        }

        public void setBurstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
        }
    }
}
