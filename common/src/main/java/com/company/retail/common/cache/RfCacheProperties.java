package com.company.retail.common.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 缓存参数（{@code retail.cache.*}）。
 *
 * <p>L1 TTL 短、L2 TTL 略长：<b>主动失效是主手段，TTL 只是兜底</b>。
 * 配置变更时写库成功即删两级缓存；万一删不掉，L2 最迟在 TTL 内收敛。</p>
 */
@ConfigurationProperties(prefix = "retail.cache")
public class RfCacheProperties {

    /** 缓存总开关；关掉后所有读走数据源。 */
    private boolean enabled = true;

    /** 键前缀（隔离用）。 */
    private String keyPrefix = "rf:";

    /** L1（Caffeine）存活时长。 */
    private Duration l1Ttl = Duration.ofSeconds(10);

    /** L2（Redis）存活时长。 */
    private Duration l2Ttl = Duration.ofSeconds(60);

    /** L1 最大条目数。 */
    private int l1MaxSize = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getL1Ttl() {
        return l1Ttl;
    }

    public void setL1Ttl(Duration l1Ttl) {
        this.l1Ttl = l1Ttl;
    }

    public Duration getL2Ttl() {
        return l2Ttl;
    }

    public void setL2Ttl(Duration l2Ttl) {
        this.l2Ttl = l2Ttl;
    }

    public int getL1MaxSize() {
        return l1MaxSize;
    }

    public void setL1MaxSize(int l1MaxSize) {
        this.l1MaxSize = l1MaxSize;
    }
}
