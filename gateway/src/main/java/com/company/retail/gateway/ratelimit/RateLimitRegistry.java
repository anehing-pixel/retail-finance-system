package com.company.retail.gateway.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 令牌桶仓库：按计数键持有桶，并按空闲时间回收。
 *
 * <p><b>回收是必须的</b>：计数键 = 段标识 + 客户端 IP，攻击者可用海量伪造来源
 * 撑爆内存；超过 {@code maxTrackedKeys} 或空闲超时即清理。</p>
 *
 * <p><b>口径边界</b>：桶在进程内存里 → 限流是<b>单实例</b>口径。
 * 网关多副本部署时每个副本各算各的，实际生效配额 ≈ 单副本配额 × 副本数；
 * 需要全局配额时再评审分布式桶（演进触发器见 {@code docs/R02 §3.4}）。</p>
 */
public final class RateLimitRegistry {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int maxTrackedKeys;
    private final long idleEvictionNanos;
    private final ScheduledExecutorService scheduler;

    public RateLimitRegistry(int maxTrackedKeys, long idleEvictionSeconds) {
        this.maxTrackedKeys = maxTrackedKeys > 0 ? maxTrackedKeys : 50_000;
        long idleNanos = Math.max(1L, idleEvictionSeconds) * 1_000_000_000L;
        this.idleEvictionNanos = idleNanos;
        long periodSeconds = Math.max(30L, Math.min(idleEvictionSeconds, 300L));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gateway-ratelimit-evict");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::evictIdleSafely,
                periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    /**
     * 取令牌：桶不存在则按策略新建。
     */
    public RateLimitDecision acquire(String key, RateLimitPolicy policy, long nowNanos) {
        Bucket bucket = buckets.computeIfAbsent(key,
                ignored -> new Bucket(new TokenBucket(policy.capacity(), policy.refillPerSecond(), nowNanos)));
        bucket.lastAccessNanos = nowNanos;
        if (buckets.size() > maxTrackedKeys) {
            evictIdle(nowNanos);
        }
        return bucket.tokens.tryAcquire(nowNanos);
    }

    /**
     * 回收空闲超时的桶，返回回收数量。
     */
    public int evictIdle(long nowNanos) {
        int removed = 0;
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            if (nowNanos - entry.getValue().lastAccessNanos > idleEvictionNanos) {
                if (buckets.remove(entry.getKey(), entry.getValue())) {
                    removed++;
                }
            }
        }
        return removed;
    }

    public int size() {
        return buckets.size();
    }

    /** 关闭回收线程（容器停机时调用）。 */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void evictIdleSafely() {
        try {
            evictIdle(System.nanoTime());
        } catch (RuntimeException ignored) {
            // 回收失败不得影响请求链路；下一轮继续
        }
    }

    private static final class Bucket {
        private final TokenBucket tokens;
        private volatile long lastAccessNanos;

        Bucket(TokenBucket tokens) {
            this.tokens = tokens;
        }
    }
}
