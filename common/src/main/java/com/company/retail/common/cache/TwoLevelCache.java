package com.company.retail.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 两级缓存：Caffeine（L1，JVM 内）+ Redis（L2，跨实例共享）。
 *
 * <p><b>用途边界（ADR-RF-012）</b>：只承载**只读业务配置**（本轮仅产品档位目录）。
 * 绝不缓存债权余额、核销流水、个人信息、审查结果——这些的权威源永远是 PostgreSQL。</p>
 *
 * <p><b>为什么自己写而不用 Spring Cache</b>：金融配置需要三件 Spring Cache 抽象给不了的东西——
 * ① <b>版本号主动失效</b>（写成功即删 L1+L2，不靠 TTL 兜底）；② <b>不可变快照</b>
 * 语义（缓存值必须是 record 之类的不可变对象，禁止可变 bean 被并发改写污染全局）；
 * ③ <b>降级口径可控</b>（Redis 不可用时直读数据源，正确性不受影响）。</p>
 *
 * <p><b>三级读取顺序</b>：L1 命中 → L2 命中 → 回源（loader）。
 * L1 走 Caffeine 的 {@code get(key, mappingFunction)}，同 JVM 内同 key 并发回源只打一次库（单飞，防击穿第一道）；
 * L2 兜住跨实例的回源放大；两者都未命中才落库。</p>
 *
 * <p><b>空值也缓存</b>：查无此项时写入哨兵，避免不存在的热点键反复打库（防穿透）。
 * L2 不可用（未装配 / 连接失败）时自动降级为 L1 + 直读数据源，<b>不抛异常、不影响正确性</b>。</p>
 *
 * <p>本类在 {@code common} 内属**机制**（横切），不含业务语义；业务只提供不可变快照与 loader。</p>
 */
public class TwoLevelCache {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);

    /** 空值哨兵（L1 内以对象身份比对）：把「查无此项」也缓存住，防穿透。 */
    private static final Object NULL_MARKER = new Object();

    private final String keyPrefix;
    private final Cache<String, Object> l1;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration l2Ttl;

    /**
     * @param keyPrefix 键前缀（多环境/多业务隔离，例如 {@code "rf:product:"}）
     * @param l1Ttl     L1 存活时长（短；兜底而非主失效手段）
     * @param l2Ttl     L2 存活时长（主动失效失败时的收敛窗口）
     * @param l1MaxSize L1 最大条目数
     * @param mapper    JSON 序列化器（需支持 JavaTimeModule；用容器托管的那个）
     * @param redis     L2 客户端；<b>可为 null</b>（未装配 Redis → 退化为 L1 + 直读）
     */
    public TwoLevelCache(String keyPrefix, Duration l1Ttl, Duration l2Ttl, int l1MaxSize,
                         ObjectMapper mapper, StringRedisTemplate redis) {
        this.keyPrefix = keyPrefix;
        this.mapper = mapper;
        this.redis = redis;
        this.l2Ttl = l2Ttl;
        this.l1 = Caffeine.newBuilder()
                .maximumSize(l1MaxSize)
                .expireAfterWrite(l1Ttl)
                .build();
    }

    /**
     * 取值：L1 → L2 → loader。
     *
     * @param key    业务键（不含前缀）
     * @param type   快照类型；<b>同一 key 的 type 必须固定</b>（缓存按 key 存对象）
     * @param loader 回源函数；返回 null 表示「查无此项」
     * @return 命中值；空 Optional 表示「查无此项」（已缓存空值）
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type, Supplier<T> loader) {
        String fullKey = fullKey(key);
        Object cached = l1.get(fullKey, k -> {
            // ② L2
            String l2 = readL2(k);
            if (l2 != null) {
                T fromL2 = deserialize(l2, type);
                if (fromL2 != null) {
                    return fromL2;
                }
            }
            // ③ 回源
            T loaded = loader.get();
            if (loaded == null) {
                writeL2(k, NULL_MARKER);
                return NULL_MARKER;
            }
            writeL2(k, loaded);
            return loaded;
        });
        if (cached == NULL_MARKER) {
            return Optional.empty();
        }
        return Optional.ofNullable((T) cached);
    }

    /**
     * 写穿：写 L2（JSON）+ L1（对象）。
     *
     * <p>调用方负责保证快照不可变；序列化失败时**跳过 L2 但保留 L1**，
     * 宁可多查一次库，也不返回错值。</p>
     */
    public <T> void put(String key, T value) {
        String fullKey = fullKey(key);
        if (value == null) {
            l1.put(fullKey, NULL_MARKER);
            writeL2(fullKey, NULL_MARKER);
            return;
        }
        l1.put(fullKey, value);
        writeL2(fullKey, value);
    }

    /**
     * 主动失效：删 L1 + 删 L2。
     *
     * <p><b>必须在数据库事务提交之后调用</b>（见 {@link CacheInvalidator}）：
     * 提交前失效会导致「失效 → 未提交 → 回源读到旧值 → 又缓存回去」的竞态。</p>
     */
    public void evict(String key) {
        String fullKey = fullKey(key);
        l1.invalidate(fullKey);
        if (redis == null) {
            return;
        }
        try {
            redis.delete(fullKey);
        } catch (Exception e) {
            // L2 删不掉不抛：L1 已清、L2 有 TTL 兜底，最坏是短窗口内读到旧配置
            log.warn("[缓存] L2 主动失效失败，等待 TTL 收敛 key={}", fullKey, e);
        }
    }

    /**
     * 按前缀批量失效（L1 全量清 + L2 按模式删）。
     *
     * <p>产品档位条目少，KEYS 模式扫描可接受；条目量级上去后应改用 SCAN。</p>
     */
    public void evictByPrefix(String prefix) {
        String fullPrefix = fullKey(prefix);
        l1.asMap().keySet().removeIf(k -> k.startsWith(fullPrefix));
        if (redis == null) {
            return;
        }
        try {
            Set<String> keys = redis.keys(fullPrefix + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception e) {
            log.warn("[缓存] L2 批量失效失败，等待 TTL 收敛 prefix={}", fullPrefix, e);
        }
    }

    /** L1 当前条目数（可观测：L2 命中率的辅助指标）。 */
    public long l1Size() {
        return l1.estimatedSize();
    }

    private String fullKey(String key) {
        return keyPrefix + key;
    }

    private String readL2(String fullKey) {
        if (redis == null) {
            return null;
        }
        try {
            return redis.opsForValue().get(fullKey);
        } catch (Exception e) {
            // Redis 不可用 → 降级直读数据源，正确性不受影响
            log.warn("[缓存] L2 读取失败，降级直读数据源 key={}", fullKey, e);
            return null;
        }
    }

    private <T> void writeL2(String fullKey, T value) {
        if (redis == null) {
            return;
        }
        String json;
        try {
            json = value == NULL_MARKER ? "__RF_NULL__" : mapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[缓存] 序列化失败，跳过 L2（保留 L1）key={}", fullKey, e);
            return;
        }
        try {
            redis.opsForValue().set(fullKey, json, l2Ttl);
        } catch (Exception e) {
            log.warn("[缓存] L2 写入失败，降级为 L1 + 数据源 key={}", fullKey, e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        if ("__RF_NULL__".equals(json)) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("[缓存] L2 反序列化失败，按未命中处理", e);
            return null;
        }
    }
}
