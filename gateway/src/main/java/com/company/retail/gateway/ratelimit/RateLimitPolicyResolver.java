package com.company.retail.gateway.ratelimit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 路径 → 限流策略解析（最长前缀优先）。
 *
 * <p>计数键 = 命中策略的段标识 + 客户端 IP，因此同一路由段的不同客户端各算各的，
 * 而同一客户端在不同路由段互不挤占。</p>
 */
public final class RateLimitPolicyResolver {

    private final RateLimitPolicy defaultPolicy;
    private final List<RateLimitRule> rules;

    public RateLimitPolicyResolver(RateLimitProperties properties) {
        RateLimitProperties.Policy fallback = properties.getDefaultPolicy();
        this.defaultPolicy = new RateLimitPolicy(fallback.getCapacity(), fallback.getRefillPerSecond());
        List<RateLimitRule> sorted = new ArrayList<>();
        for (RateLimitProperties.Rule rule : properties.getRules()) {
            sorted.add(new RateLimitRule(rule.getPathPrefix(), rule.getCapacity(), rule.getRefillPerSecond()));
        }
        sorted.sort(Comparator.comparingInt((RateLimitRule r) -> r.pathPrefix().length()).reversed());
        this.rules = List.copyOf(sorted);
    }

    /**
     * 按请求路径解析策略。
     *
     * @param path 网关侧请求路径（不含查询串）
     */
    public Resolved resolve(String path) {
        String target = path == null ? "" : path;
        for (RateLimitRule rule : rules) {
            if (target.startsWith(rule.pathPrefix())) {
                return new Resolved(rule.pathPrefix(), rule.policy());
            }
        }
        return new Resolved("default", defaultPolicy);
    }

    /**
     * 解析结果。
     *
     * @param segment 命中的段标识（规则前缀或 {@code default}），同时作为计数键的一段
     * @param policy  该段使用的限流策略
     */
    public record Resolved(String segment, RateLimitPolicy policy) {
    }
}
