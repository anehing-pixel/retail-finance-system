package com.company.retail.common.lock;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 分布式锁装配（各服务 {@code @Import}，精确装配纪律：不扫全包）。
 *
 * <p><b>启动即 fail-closed</b>：{@link Redisson#create} 在 Redis 不可达时抛异常，
 * bean 创建失败 → 服务启动失败，而不是「起来之后再在写路径上炸」。
 * 受保护写路径的服务没有 Redis 就起不来，这是刻意设计。</p>
 *
 * <p>配置键（{@code retail.*} 前缀，R00 §7）：</p>
 * <ul>
 *   <li>{@code retail.lock.redis.address}：{@code redis://host:port}（默认 127.0.0.1:6380）</li>
 *   <li>{@code retail.lock.redis.connect-timeout-ms} / {@code timeout-ms}：连接与命令超时</li>
 *   <li>{@code retail.lock.redis.retry-attempts}：连接重试次数（默认 0 = 不重试，快速失败）</li>
 * </ul>
 */
@Configuration
public class RfLockConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${retail.lock.redis.address:redis://127.0.0.1:6380}") String address,
            @Value("${retail.lock.redis.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${retail.lock.redis.timeout-ms:3000}") int timeoutMs,
            @Value("${retail.lock.redis.retry-attempts:0}") int retryAttempts) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(address)
                .setConnectTimeout(connectTimeoutMs)
                .setTimeout(timeoutMs)
                .setRetryAttempts(retryAttempts);
        return Redisson.create(config);
    }

    @Bean
    public DistributedLockManager distributedLockManager(RedissonClient redissonClient) {
        return new RedissonLockManager(redissonClient);
    }
}
