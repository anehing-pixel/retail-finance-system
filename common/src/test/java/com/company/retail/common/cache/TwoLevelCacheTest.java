package com.company.retail.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 两级缓存行为单测（Caffeine L1 + Redis L2）。
 *
 * <p>覆盖：L1 命中、L2 命中、空值缓存（防穿透）、Redis 不可用降级、主动失效、写穿、
 * 不可变快照的序列化往返（含 LocalDate 与整数口径）。</p>
 */
class TwoLevelCacheTest {

    /** 业务快照样例：不可变 record（生产侧产品档位档案同形）。 */
    record ProductSnapshot(String productCode, int termCount, int rateBp, LocalDate effectiveFrom) {
    }

    private static final String KEY = "product:AUTO_LOAN_36_0";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ObjectMapper mapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private TwoLevelCache cache() {
        return new TwoLevelCache("rf:test:", Duration.ofSeconds(10), Duration.ofSeconds(60),
                100, mapper, redis);
    }

    private ProductSnapshot sample() {
        return new ProductSnapshot("AUTO_LOAN_36_0", 36, 0, LocalDate.of(2026, 1, 1));
    }

    @Test
    void l1Hit_secondCallDoesNotInvokeLoader() {
        TwoLevelCache cache = cache();
        AtomicInteger calls = new AtomicInteger();

        Optional<ProductSnapshot> first = cache.get(KEY, ProductSnapshot.class, () -> {
            calls.incrementAndGet();
            return sample();
        });
        Optional<ProductSnapshot> second = cache.get(KEY, ProductSnapshot.class, () -> {
            calls.incrementAndGet();
            return sample();
        });

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertEquals(1, calls.get(), "L1 命中后不应再次回源");
        assertEquals("AUTO_LOAN_36_0", second.get().productCode());
    }

    @Test
    void l2Hit_whenL1Empty_doesNotInvokeLoader() throws Exception {
        TwoLevelCache cache = cache();
        when(ops.get(anyString())).thenReturn(mapper.writeValueAsString(sample()));
        AtomicInteger calls = new AtomicInteger();

        Optional<ProductSnapshot> hit = cache.get(KEY, ProductSnapshot.class, () -> {
            calls.incrementAndGet();
            return new ProductSnapshot("OTHER", 12, 999, LocalDate.of(2030, 1, 1));
        });

        assertTrue(hit.isPresent());
        assertEquals(36, hit.get().termCount());
        assertEquals(0, calls.get(), "L2 命中不应回源");
    }

    @Test
    void miss_isCachedAsNullSentinel_soLoaderNotCalledAgain() {
        TwoLevelCache cache = cache();
        AtomicInteger calls = new AtomicInteger();

        Optional<ProductSnapshot> first = cache.get(KEY, ProductSnapshot.class, () -> {
            calls.incrementAndGet();
            return null;
        });
        Optional<ProductSnapshot> second = cache.get(KEY, ProductSnapshot.class, () -> {
            calls.incrementAndGet();
            return null;
        });

        assertFalse(first.isPresent());
        assertFalse(second.isPresent());
        assertEquals(1, calls.get(), "空值应被缓存住（防穿透），不应反复回源");
    }

    @Test
    void redisUnavailable_fallsBackToLoader_andStillReturnsCorrectValue() {
        TwoLevelCache cache = cache();
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));
        AtomicInteger calls = new AtomicInteger();

        Optional<ProductSnapshot> hit = cache.get(KEY, ProductSnapshot.class, () -> {
            calls.incrementAndGet();
            return sample();
        });

        assertTrue(hit.isPresent(), "Redis 不可用时应降级直读数据源，不影响正确性");
        assertEquals(36, hit.get().termCount());
        assertEquals(1, calls.get());
    }

    @Test
    void evict_clearsBothLevels_soNextReadReloads() {
        TwoLevelCache cache = cache();
        AtomicInteger calls = new AtomicInteger();

        cache.get(KEY, ProductSnapshot.class, () -> {
            calls.incrementAndGet();
            return sample();
        });
        cache.evict(KEY);
        cache.get(KEY, ProductSnapshot.class, () -> {
            calls.incrementAndGet();
            return sample();
        });

        assertEquals(2, calls.get(), "主动失效后应重新回源");
        verify(redis).delete("rf:test:" + KEY);
    }

    @Test
    void put_populatesBothLevels() {
        TwoLevelCache cache = cache();

        cache.put(KEY, sample());
        Optional<ProductSnapshot> hit = cache.get(KEY, ProductSnapshot.class, () -> {
            throw new IllegalStateException("不应回源");
        });

        assertTrue(hit.isPresent());
        verify(ops).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void snapshotRoundTrip_preservesLocalDateAndIntegers() throws Exception {
        TwoLevelCache cache = cache();
        ProductSnapshot original = new ProductSnapshot("INSTALMENT_36_200", 36, 200,
                LocalDate.of(2026, 7, 1));

        cache.put(KEY, original);
        // 绕过 L1 直接验 L2 里的 JSON 往返
        String json = mapper.writeValueAsString(original);
        ProductSnapshot parsed = mapper.readValue(json, ProductSnapshot.class);

        assertEquals(original, parsed);
        assertEquals(LocalDate.of(2026, 7, 1), parsed.effectiveFrom());
        assertEquals(200, parsed.rateBp());
    }

    @Test
    void evictByPrefix_clearsMatchingKeysOnly() {
        TwoLevelCache cache = cache();
        AtomicInteger calls = new AtomicInteger();

        cache.get("product:A", ProductSnapshot.class, () -> {
            calls.incrementAndGet();
            return sample();
        });
        cache.get("product:B", ProductSnapshot.class, () -> {
            calls.incrementAndGet();
            return sample();
        });
        cache.get("other:C", ProductSnapshot.class, () -> {
            calls.incrementAndGet();
            return sample();
        });

        cache.evictByPrefix("product:");

        cache.get("product:A", ProductSnapshot.class, () -> {
            calls.incrementAndGet();
            return sample();
        });
        cache.get("product:B", ProductSnapshot.class, () -> {
            calls.incrementAndGet();
            return sample();
        });
        cache.get("other:C", ProductSnapshot.class, () -> {
            calls.incrementAndGet();
            return sample();
        });

        assertEquals(5, calls.get(), "前缀内两个 key 应失效重取，前缀外的不受影响");
    }

    @Test
    void withoutRedisBean_degradesToL1Only() {
        TwoLevelCache cache = new TwoLevelCache("rf:test:", Duration.ofSeconds(10),
                Duration.ofSeconds(60), 100, mapper, null);
        AtomicInteger calls = new AtomicInteger();

        cache.get(KEY, ProductSnapshot.class, () -> {
            calls.incrementAndGet();
            return sample();
        });
        cache.get(KEY, ProductSnapshot.class, () -> {
            calls.incrementAndGet();
            return sample();
        });

        assertEquals(1, calls.get(), "未装配 Redis 时退化为 L1，仍应有命中");
        verify(redis, never()).opsForValue();
    }
}
