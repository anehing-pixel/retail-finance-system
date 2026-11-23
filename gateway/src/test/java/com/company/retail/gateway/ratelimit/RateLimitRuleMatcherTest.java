package com.company.retail.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 限流规则匹配单测：最具体优先、无命中不限流。
 */
class RateLimitRuleMatcherTest {

    private static RateLimitProperties.Rule rule(String path, int rate, int burst) {
        RateLimitProperties.Rule r = new RateLimitProperties.Rule();
        r.setPath(path);
        r.setReplenishRate(rate);
        r.setBurstCapacity(burst);
        return r;
    }

    @Test
    void noRule_match_returnsNull() {
        RateLimitRuleMatcher matcher = new RateLimitRuleMatcher(List.of(rule("/api/**", 100, 200)));
        assertNull(matcher.match("/health"));
    }

    @Test
    void specificRule_wins_over_generalRule() {
        RateLimitProperties.Rule general = rule("/api/**", 500, 1000);
        RateLimitProperties.Rule specific = rule("/api/applications/**", 20, 40);
        RateLimitRuleMatcher matcher = new RateLimitRuleMatcher(List.of(general, specific));

        assertSame(specific, matcher.match("/api/applications/x"));
        assertSame(general, matcher.match("/api/loans/x"));
    }

    @Test
    void longestPattern_wins_whenSeveralMatch() {
        RateLimitProperties.Rule a = rule("/api/**", 500, 1000);
        RateLimitProperties.Rule b = rule("/api/credit-inquiries/**", 10, 20);
        RateLimitProperties.Rule c = rule("/api/credit-inquiries/detail/**", 5, 10);
        RateLimitRuleMatcher matcher = new RateLimitRuleMatcher(List.of(a, b, c));

        assertSame(c, matcher.match("/api/credit-inquiries/detail/1"));
        assertSame(b, matcher.match("/api/credit-inquiries/x"));
    }

    @Test
    void emptyRules_returnsNull() {
        assertNull(new RateLimitRuleMatcher(List.of()).match("/api/x"));
    }
}
