package com.company.retail.gateway.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 路径 → 策略解析：最长前缀优先，未命中回落默认。
 */
class RateLimitPolicyResolverTest {

    private RateLimitPolicyResolver resolver(List<RateLimitProperties.Rule> rules) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setDefaultPolicy(new RateLimitProperties.Policy(600, 100d));
        properties.setRules(rules);
        return new RateLimitPolicyResolver(properties);
    }

    @Test
    @DisplayName("未命中任何规则时使用默认策略")
    void fallsBackToDefault() {
        RateLimitPolicyResolver resolver = resolver(List.of(new RateLimitProperties.Rule("/api/auth/", 10, 1d)));
        RateLimitPolicyResolver.Resolved resolved = resolver.resolve("/api/loans/1");
        assertEquals("default", resolved.segment());
        assertEquals(600, resolved.policy().capacity());
    }

    @Test
    @DisplayName("命中规则时取该规则的策略")
    void matchesRule() {
        RateLimitPolicyResolver resolver = resolver(List.of(new RateLimitProperties.Rule("/api/auth/", 10, 1d)));
        RateLimitPolicyResolver.Resolved resolved = resolver.resolve("/api/auth/login");
        assertEquals("/api/auth/", resolved.segment());
        assertEquals(10, resolved.policy().capacity());
    }

    @Test
    @DisplayName("多条规则命中时取最长前缀")
    void longestPrefixWins() {
        RateLimitPolicyResolver resolver = resolver(List.of(
                new RateLimitProperties.Rule("/api/", 100, 10d),
                new RateLimitProperties.Rule("/api/auth/", 10, 1d),
                new RateLimitProperties.Rule("/api/auth/refresh/", 5, 0.5d)));
        assertEquals("/api/auth/refresh/", resolver.resolve("/api/auth/refresh/x").segment());
        assertEquals("/api/auth/", resolver.resolve("/api/auth/login").segment());
        assertEquals("/api/", resolver.resolve("/api/loans").segment());
    }
}
