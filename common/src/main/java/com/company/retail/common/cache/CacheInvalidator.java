package com.company.retail.common.cache;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 缓存失效时机：<b>事务提交后</b>再失效。
 *
 * <p>为什么不能在事务内失效：写库与缓存失效在同一事务里时，失效动作发生在提交之前，
 * 此刻其他线程回源读到的仍是**未提交的旧值**，并把它重新写回缓存——
 * 于是「刚改完的配置」又变成了旧值，而且直到 TTL 到期都错。</p>
 *
 * <p>正确顺序：写库 → 提交 → 失效（删 L1 + 删 L2）。
 * 本类在有活动事务时注册 afterCommit 回调；无事务时立即失效。</p>
 */
public final class CacheInvalidator {

    private CacheInvalidator() {
    }

    /**
     * 在当前事务提交后失效指定 key；无活动事务时立即失效。
     *
     * @param cache 缓存实例
     * @param key   业务键（不含前缀）
     */
    public static void evictAfterCommit(TwoLevelCache cache, String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.evict(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.evict(key);
            }
        });
    }
}
