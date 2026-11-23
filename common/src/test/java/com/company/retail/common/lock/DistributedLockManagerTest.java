package com.company.retail.common.lock;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分布式锁机制件单测（ADR-RF-015）。
 *
 * <p>覆盖：锁键命名（用途分域 + 不落明文业务标识）、单进程锁的互斥与超时语义、
 * Redisson 适配器的 fail-closed 与「锁在事务提交后释放」时序。</p>
 */
class DistributedLockManagerTest {

    // ---------- 锁键命名 ----------

    @Test
    void 锁键_用途前缀且不含明文业务标识() {
        String key = LockKeys.applicationDedup(1001L, "VIN-SECRET-001");

        assertTrue(key.startsWith("retail:lock:app-dedup:"), "锁键必须带用途前缀：" + key);
        assertFalse(key.contains("VIN-SECRET-001"), "锁键不得落明文 VIN：" + key);
        assertEquals("retail:lock:app-dedup:" + key.substring("retail:lock:app-dedup:".length()), key);
    }

    @Test
    void 锁键_同输入同键_不同输入不同键() {
        assertEquals(LockKeys.applicationDedup(1001L, "VIN-A"), LockKeys.applicationDedup(1001L, "VIN-A"));
        assertNotEquals(LockKeys.applicationDedup(1001L, "VIN-A"), LockKeys.applicationDedup(1002L, "VIN-A"));
        assertNotEquals(LockKeys.applicationDedup(1001L, "VIN-A"), LockKeys.applicationDedup(1001L, "VIN-B"));
    }

    @Test
    void 锁键_DMZ入站与回执登记分域_避免跨进程自死锁() {
        assertNotEquals(LockKeys.dmzCallback(7L, "RCP-1"), LockKeys.registryReceipt(7L, "RCP-1"),
                "DMZ 持锁期间会调用 title-release，两者不得共用同一把锁");
    }

    // ---------- 单进程锁（单测替身） ----------

    @Test
    void 单进程锁_同键互斥_两个线程临界区不重叠() throws Exception {
        InProcessLockManager locks = new InProcessLockManager();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger overlap = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        String key = LockKeys.applicationDedup(1L, "VIN-MUTEX");

        Runnable body = () -> {
            if (active.incrementAndGet() > 1) {
                overlap.incrementAndGet();
            }
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
        };

        Thread t1 = new Thread(() -> {
            await(start);
            locks.runWithLock(key, Duration.ofSeconds(2), body);
        });
        Thread t2 = new Thread(() -> {
            await(start);
            locks.runWithLock(key, Duration.ofSeconds(2), body);
        });
        t1.start();
        t2.start();
        start.countDown();
        t1.join(5000);
        t2.join(5000);

        assertEquals(0, overlap.get(), "同键临界区不得重叠");
    }

    @Test
    void 单进程锁_等待超时_按并发冲突拒绝() throws Exception {
        InProcessLockManager locks = new InProcessLockManager();
        String key = LockKeys.applicationDedup(2L, "VIN-BUSY");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = new Thread(() -> locks.runWithLock(key, Duration.ofSeconds(1), () -> {
            holding.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        assertTrue(holding.await(5, TimeUnit.SECONDS));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> locks.runWithLock(key, Duration.ofMillis(100), () -> { }));
        assertEquals(RFErrorCode.RF_LOCK_001, ex.getErrorCode());
        assertEquals(409, ex.getErrorCode().getHttpStatus());

        release.countDown();
        holder.join(5000);
    }

    // ---------- Redisson 适配器 ----------

    @Test
    void Redisson适配器_抢锁失败_按并发冲突拒绝() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);
        RedissonLockManager manager = new RedissonLockManager(clientOf(lock));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> manager.callWithLock("k", Duration.ofMillis(50), () -> "x"));
        assertEquals(RFErrorCode.RF_LOCK_001, ex.getErrorCode());
    }

    @Test
    void Redisson适配器_Redis不可达_failClosed拒绝写() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenThrow(new RedisConnectionException("down"));
        RedissonLockManager manager = new RedissonLockManager(clientOf(lock));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> manager.callWithLock("k", Duration.ofMillis(50), () -> "x"));
        assertEquals(RFErrorCode.RF_LOCK_002, ex.getErrorCode());
        assertEquals(503, ex.getErrorCode().getHttpStatus(), "锁服务不可用必须是 503，不得降级放行");
    }

    @Test
    void Redisson适配器_Redis不可达_包装异常也failClosed() throws Exception {
        // 实测（Redis 宕机）：Redisson 抛包装异常 RedisException("Unexpected exception...")，
        // 不是直接的 RedisConnectionException——漏判会落成 500 SYS_001。
        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), any(TimeUnit.class)))
                .thenThrow(new RedisException("Unexpected exception while processing command",
                        new RedisConnectionException("down")));
        RedissonLockManager manager = new RedissonLockManager(clientOf(lock));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> manager.callWithLock("k", Duration.ofMillis(50), () -> "x"));
        assertEquals(RFErrorCode.RF_LOCK_002, ex.getErrorCode());
        assertEquals(503, ex.getErrorCode().getHttpStatus());
    }

    @Test
    void Redisson适配器_非Redis异常_原样抛出不被误判为锁不可用() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), any(TimeUnit.class)))
                .thenThrow(new IllegalStateException("业务异常"));
        RedissonLockManager manager = new RedissonLockManager(clientOf(lock));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> manager.callWithLock("k", Duration.ofMillis(50), () -> "x"));
        assertEquals("业务异常", ex.getMessage());
    }

    @Test
    void Redisson适配器_非事务_临界区结束即释放() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        RedissonLockManager manager = new RedissonLockManager(clientOf(lock));

        String out = manager.callWithLock("k", Duration.ofSeconds(1), () -> "ok");

        assertEquals("ok", out);
        verify(lock).unlock();
    }

    @Test
    void Redisson适配器_事务内_锁在事务提交后才释放() throws Exception {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        RedissonLockManager manager = new RedissonLockManager(clientOf(lock));

        TransactionSynchronizationManager.initSynchronization();
        try {
            manager.callWithLock("k", Duration.ofSeconds(1), () -> "ok");
            verify(lock, never()).unlock();
            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, syncs.size());
            syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            verify(lock).unlock();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------- 辅助 ----------

    private RedissonClient clientOf(RLock lock) {
        RedissonClient client = mock(RedissonClient.class);
        when(client.getLock(anyString())).thenReturn(lock);
        return client;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
