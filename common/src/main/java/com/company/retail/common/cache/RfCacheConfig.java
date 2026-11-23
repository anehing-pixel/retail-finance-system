package com.company.retail.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 缓存装配（共享模块的 bean <b>不进全包扫描</b>，由各服务用 {@code @Import} 按类注入，
 * 与 {@code RfSecurityConfig} / {@code RfOutboxConfig} 同款精确装配口径）。
 *
 * <p>{@link StringRedisTemplate} 用 {@link ObjectProvider} 取：<b>未装配 Redis 的服务
 * 也能启动</b>（退化为 L1 + 直读数据源），不因为缓存把整个服务拖挂。</p>
 */
@Configuration
@EnableConfigurationProperties(RfCacheProperties.class)
@ConditionalOnClass(Caffeine.class)
public class RfCacheConfig {

    @Bean
    public TwoLevelCache twoLevelCache(RfCacheProperties props, ObjectMapper mapper,
                                        ObjectProvider<StringRedisTemplate> redisProvider) {
        return new TwoLevelCache(props.getKeyPrefix(), props.getL1Ttl(), props.getL2Ttl(),
                props.getL1MaxSize(), mapper, redisProvider.getIfAvailable());
    }
}
