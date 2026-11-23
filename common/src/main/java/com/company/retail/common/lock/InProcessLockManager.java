package com.company.retail.common.lock;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 单进程内锁（<b>仅供单测与单机开发</b>；多实例部署禁用）。
 *
 * <p>存在的唯一理由：让服务层的锁语义可以在没有 Redis 的环境里被确定性验证
 * （单测不依赖外部中间件）。它<b>不是</b>分布式锁——多实例部署下互斥失效。
 * 生产装配只注册 {@link RedissonLockManager}，本类不进任何 {@code @Configuration}。</p>
 *
 * <p>与生产适配器的差异：本实现不感知事务边界（临界区结束即释放），
 * 单进程内不存在「锁已释放但事务未提交」的跨进程竞态。</p>
 */
public class InProcessLockManager implements DistributedLockManager {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T callWithLock(String lockKey, Duration waitTime, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(lockKey, key -> new ReentrantLock());
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new BusinessException(RFErrorCode.RF_LOCK_001,
                        Map.of("lockKey", lockKey, "waitMs", String.valueOf(waitTime.toMillis())));
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(RFErrorCode.RF_LOCK_001,
                    Map.of("lockKey", lockKey, "reason", "INTERRUPTED"));
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
    }
}
