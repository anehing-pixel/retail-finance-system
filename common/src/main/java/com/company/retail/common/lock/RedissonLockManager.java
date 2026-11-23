package com.company.retail.common.lock;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redisson 分布式锁适配器（ADR-RF-012）。
 *
 * <p><b>fail-closed</b>：Redis 不可达 → 抛 {@code RF_LOCK_002}（503）拒绝写；
 * 等待超时（并发冲突）→ 抛 {@code RF_LOCK_001}（409）。两者都<b>不降级放行</b>——
 * 金融写路径宁可拒绝错，不可放行错（{@code AGENTS.md} §8 fail-closed 纪律）。</p>
 *
 * <p><b>释放时机对齐事务</b>：临界区在事务内执行时，锁在
 * {@code TransactionSynchronization#afterCompletion}（提交或回滚之后）才释放；
 * 不在事务内（如 DMZ 转发）则临界区结束即释放。这条顺序是「先查后插」竞态的
 * 关键——锁先释放、事务后提交，下一个请求就会查不到刚写的行。</p>
 *
 * <p><b>看门狗续期</b>：用 {@code tryLock(wait, unit)}（不带 lease）启用 Redisson 看门狗，
 * 临界区超过默认 30s 租约时自动续期，避免长事务中途锁被释放。</p>
 */
public class RedissonLockManager implements DistributedLockManager {

    private static final Logger log = LoggerFactory.getLogger(RedissonLockManager.class);

    private final RedissonClient redisson;

    public RedissonLockManager(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public <T> T callWithLock(String lockKey, Duration waitTime, Supplier<T> action) {
        RLock lock = redisson.getLock(lockKey);
        boolean acquired;
        try {
            acquired = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(RFErrorCode.RF_LOCK_001,
                    Map.of("lockKey", lockKey, "reason", "INTERRUPTED"));
        } catch (RedisConnectionException | RedisTimeoutException e) {
            // 锁服务不可用 → fail-closed 拒绝写（不降级放行）
            throw new BusinessException(RFErrorCode.RF_LOCK_002,
                    Map.of("lockKey", lockKey, "reason", "REDIS_UNAVAILABLE"));
        }
        if (!acquired) {
            throw new BusinessException(RFErrorCode.RF_LOCK_001,
                    Map.of("lockKey", lockKey, "waitMs", String.valueOf(waitTime.toMillis())));
        }
        boolean releaseAfterTransaction = TransactionSynchronizationManager.isSynchronizationActive();
        if (releaseAfterTransaction) {
            final RLock held = lock;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    unlock(held, lockKey);
                }
            });
        }
        try {
            return action.get();
        } finally {
            if (!releaseAfterTransaction) {
                unlock(lock, lockKey);
            }
        }
    }

    private void unlock(RLock lock, String lockKey) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (IllegalMonitorStateException e) {
            log.warn("分布式锁释放异常（锁已失效）lockKey={}", lockKey);
        }
    }
}
