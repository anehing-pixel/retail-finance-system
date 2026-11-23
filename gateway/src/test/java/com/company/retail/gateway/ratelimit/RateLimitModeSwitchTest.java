package com.company.retail.gateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流模式开关的<b>装配互斥</b>闸门。
 *
 * <p>网关有两套限流实现（Redis 令牌桶 / 进程内令牌桶）。若同时装配，
 * 两个 {@code GlobalFilter} 会叠加生效 → 请求被<b>双重限流</b>，额度减半且难以排查。
 * 本测试在装配期钉死：任意时刻只注册一个限流过滤器。</p>
 *
 * <p>用 {@code ApplicationContextRunner} 起真实上下文，只把 Redis 连接替成桩
 * （限流属于保护层，单测阶段不应真连 Redis）。</p>
 */
class RateLimitModeSwitchTest {

    private final ReactiveStringRedisTemplate redisTemplate = Mockito.mock(ReactiveStringRedisTemplate.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RateLimitConfig.class, InMemoryRateLimitConfig.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(TokenBucketLimiter.class, () -> new TokenBucketLimiter(redisTemplate));

    @Test
    @DisplayName("未配置 mode 时默认装配 Redis 令牌桶实现")
    void defaultsToRedisMode() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(RateLimitFilter.class);
            assertThat(context).doesNotHaveBean(InMemoryRateLimitFilter.class);
            assertThat(context).doesNotHaveBean(RateLimitRegistry.class);
        });
    }

    @Test
    @DisplayName("mode=redis 时只装配 Redis 令牌桶实现")
    void redisModeOnly() {
        runner.withPropertyValues("retail.gateway.rate-limit.mode=redis").run(context -> {
            assertThat(context).hasSingleBean(RateLimitFilter.class);
            assertThat(context).doesNotHaveBean(InMemoryRateLimitFilter.class);
            assertThat(context).doesNotHaveBean(RateLimitRegistry.class);
        });
    }

    @Test
    @DisplayName("mode=memory 时只装配进程内令牌桶实现")
    void memoryModeOnly() {
        runner.withPropertyValues("retail.gateway.rate-limit.mode=memory").run(context -> {
            assertThat(context).hasSingleBean(InMemoryRateLimitFilter.class);
            assertThat(context).doesNotHaveBean(RateLimitFilter.class);
            assertThat(context).hasSingleBean(RateLimitRegistry.class);
            assertThat(context).hasSingleBean(ClientIpResolver.class);
        });
    }
}
