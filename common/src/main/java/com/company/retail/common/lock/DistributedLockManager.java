package com.company.retail.common.lock;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 分布式锁端口（横切机制层，{@code common}；ADR-RF-013）。
 *
 * <p><b>定位</b>：锁只负责<b>串行化并发窗口</b>，<b>不是正确性来源</b>——
 * 幂等的最终权威仍是数据库唯一索引与业务唯一键（{@code AGENTS.md} §2）。
 * 锁不可用时必须 <b>fail-closed</b>（拒绝写），不得降级放行。</p>
 *
 * <p><b>事务边界</b>：实现方负责把释放时机对齐到事务提交之后
 * （生产适配器用 {@code TransactionSynchronization#afterCompletion}），
 * 避免「锁已释放但事务未提交」的窗口——那正是先查后插竞态的成因。</p>
 */
public interface DistributedLockManager {

    /**
     * 在分布式锁保护下执行 {@code action}。
     *
     * @param lockKey  锁键（由 {@link LockKeys} 生成，不含明文业务标识）
     * @param waitTime 等待锁的最长时间；超时按并发冲突拒绝（{@code RF_LOCK_001}）
     * @param action   临界区逻辑
     * @return 临界区返回值
     * @throws com.company.platform.exception.BusinessException 拿不到锁（{@code RF_LOCK_001}）
     *                                                 或锁服务不可用（{@code RF_LOCK_002}）
     */
    <T> T callWithLock(String lockKey, Duration waitTime, Supplier<T> action);

    /** 无返回值的临界区。 */
    default void runWithLock(String lockKey, Duration waitTime, Runnable action) {
        callWithLock(lockKey, waitTime, () -> {
            action.run();
            return null;
        });
    }
}
