package com.company.retail.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关限流参数（配置键 {@code retail.ratelimit.*}，环境变量 {@code RETAIL_RATELIMIT_*}）。
 *
 * <p><b>默认参数写在代码里而不是 {@code gateway/application.yml}</b>：后者属
 * {@code AGENTS.md §7} 共享禁区文件，且网关参数在生产主要以环境变量下发。
 * 需要覆盖时用环境变量（{@code RETAIL_RATELIMIT_ENABLED} 等）或
 * {@code --retail.ratelimit.*} 启动参数。</p>
 *
 * <p>默认值为<b>建议起始值</b>（⚙️ 可配置覆盖），不是业务裁决：桶容量即突发额度，
 * 补充速率即稳态吞吐；上线前按真实流量重标。</p>
 */
@ConfigurationProperties(prefix = "retail.ratelimit")
public class RateLimitProperties {

    /** 总开关；关闭后网关完全放行（仅用于排障与应急，建议保持开启）。 */
    private boolean enabled = true;

    /** 计数键上限（桶数量），超过后按空闲时间回收，防止内存被恶意 IP 撑爆。 */
    private int maxTrackedKeys = 50_000;

    /** 空闲回收阈值（秒）：桶在该时长内未被命中即回收。 */
    private long idleEvictionSeconds = 600L;

    /**
     * 可信代理网段（逗号分隔的 CIDR）。<b>默认空 = 不采信任何转发头</b>。
     *
     * <p>直连来源不在此列表时，网关只认 TCP 直连地址，忽略 X-Forwarded-For，
     * 防止「换一个头就能重置限流」。生产在网关前置 Nginx 时，
     * 必须把 Nginx 所在网段登记进来，否则所有客户端会被算成 Nginx 一个来源、
     * 共享同一个桶。</p>
     */
    private String trustedProxyCidrs = "";

    /** 默认策略（未命中任何规则前缀时使用）。 */
    private Policy defaultPolicy = new Policy(600, 100d);

    /** 路径级规则，按前缀长度倒序匹配（最长前缀优先）。 */
    private List<Rule> rules = new ArrayList<>(List.of(
            new Rule("/api/auth/", 120, 20d),
            new Rule("/api/applications/", 120, 20d),
            new Rule("/api/consents/", 120, 20d),
            new Rule("/api/identity-verifications/", 60, 10d),
            new Rule("/api/customer-self/", 120, 20d)));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxTrackedKeys() {
        return maxTrackedKeys;
    }

    public void setMaxTrackedKeys(int maxTrackedKeys) {
        this.maxTrackedKeys = maxTrackedKeys;
    }

    public long getIdleEvictionSeconds() {
        return idleEvictionSeconds;
    }

    public void setIdleEvictionSeconds(long idleEvictionSeconds) {
        this.idleEvictionSeconds = idleEvictionSeconds;
    }

    public String getTrustedProxyCidrs() {
        return trustedProxyCidrs;
    }

    public void setTrustedProxyCidrs(String trustedProxyCidrs) {
        this.trustedProxyCidrs = trustedProxyCidrs;
    }

    public Policy getDefaultPolicy() {
        return defaultPolicy;
    }

    public void setDefaultPolicy(Policy defaultPolicy) {
        this.defaultPolicy = defaultPolicy;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules;
    }

    /** 默认策略 / 规则里的容量与速率。 */
    public static class Policy {
        private int capacity = 600;
        private double refillPerSecond = 100d;

        public Policy() {
        }

        public Policy(int capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public double getRefillPerSecond() {
            return refillPerSecond;
        }

        public void setRefillPerSecond(double refillPerSecond) {
            this.refillPerSecond = refillPerSecond;
        }
    }

    /** 路径级规则项。 */
    public static class Rule {
        private String pathPrefix;
        private int capacity = 120;
        private double refillPerSecond = 20d;

        public Rule() {
        }

        public Rule(String pathPrefix, int capacity, double refillPerSecond) {
            this.pathPrefix = pathPrefix;
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
        }

        public String getPathPrefix() {
            return pathPrefix;
        }

        public void setPathPrefix(String pathPrefix) {
            this.pathPrefix = pathPrefix;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public double getRefillPerSecond() {
            return refillPerSecond;
        }

        public void setRefillPerSecond(double refillPerSecond) {
            this.refillPerSecond = refillPerSecond;
        }
    }
}
