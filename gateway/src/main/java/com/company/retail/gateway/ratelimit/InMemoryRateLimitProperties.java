package com.company.retail.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 进程内限流参数（{@code retail.gateway.rate-limit.memory.*}，环境变量 {@code RETAIL_GATEWAY_RATE_LIMIT_MEMORY_*}）。
 *
 * <p><b>只在 {@code retail.gateway.rate-limit.mode=memory} 时生效</b>：网关限流有两套实现
 * ——Redis 令牌桶（分布式准确，默认）与本类的进程内令牌桶（单实例口径、零外部依赖），
 * 由 {@code mode} 一个开关二选一，见 {@code R02 §3.3}。</p>
 *
 * <p><b>默认参数写在代码里而不是 {@code gateway/application.yml}</b>：后者属
 * {@code AGENTS.md §7} 共享禁区文件，且网关参数在生产主要以环境变量下发。
 * 需要覆盖时用环境变量（{@code RETAIL_GATEWAY_RATE_LIMIT_MEMORY_ENABLED} 等）或
 * {@code --retail.gateway.rate-limit.memory.*} 启动参数。</p>
 *
 * <p>默认值为<b>建议起始值</b>（⚙️ 可配置覆盖），不是业务裁决：桶容量即突发额度，
 * 补充速率即稳态吞吐；上线前按真实流量重标。</p>
 *
 * <p><b>口径边界</b>：桶在进程内存里 → 限流是<b>单实例</b>口径。
 * 网关多副本部署时每个副本各算各的，实际生效配额 ≈ 单副本配额 × 副本数；
 * 需要全局配额时切 {@code mode=redis}（Redis 是唯一共享点）。</p>
 */
@ConfigurationProperties(prefix = "retail.gateway.rate-limit.memory")
public class InMemoryRateLimitProperties {

    /** 总开关；关闭后本模式完全放行（仅用于排障与应急，建议保持开启）。 */
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
        this.trustedProxyCidrs = trustedProxyCidrs == null ? "" : trustedProxyCidrs;
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
        this.rules = rules == null ? new ArrayList<>() : rules;
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
