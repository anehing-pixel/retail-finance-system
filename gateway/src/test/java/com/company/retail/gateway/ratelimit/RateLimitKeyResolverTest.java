package com.company.retail.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流键解析单测：凭据不落键、不同调用方分桶、ip 维度强制走 IP。
 */
class RateLimitKeyResolverTest {

    @Test
    void authHeader_isHashed_notStoredInPlainText() {
        String key = RateLimitKeyResolver.resolve("Bearer super-secret-token", "127.0.0.1", "auth_or_ip");
        assertTrue(key.startsWith("a:"));
        assertTrue(!key.contains("super-secret-token"), "凭据本身不得出现在 Redis 键里");
    }

    @Test
    void differentCallers_getDifferentBuckets() {
        String a = RateLimitKeyResolver.resolve("Bearer token-a", "127.0.0.1", "auth_or_ip");
        String b = RateLimitKeyResolver.resolve("Bearer token-b", "127.0.0.1", "auth_or_ip");
        assertNotEquals(a, b);
    }

    @Test
    void sameCaller_sameBucket() {
        String a = RateLimitKeyResolver.resolve("Bearer token-a", "127.0.0.1", "auth_or_ip");
        String b = RateLimitKeyResolver.resolve("Bearer token-a", "127.0.0.1", "auth_or_ip");
        assertEquals(a, b);
    }

    @Test
    void noAuthHeader_fallsBackToIp() {
        assertEquals("ip:10.0.0.7", RateLimitKeyResolver.resolve(null, "10.0.0.7", "auth_or_ip"));
        assertEquals("ip:10.0.0.7", RateLimitKeyResolver.resolve("   ", "10.0.0.7", "auth_or_ip"));
    }

    @Test
    void keyByIp_ignoresAuthHeader() {
        assertEquals("ip:10.0.0.7",
                RateLimitKeyResolver.resolve("Bearer token-a", "10.0.0.7", "ip"));
    }
}
