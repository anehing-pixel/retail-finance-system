package com.company.retail.gateway.ratelimit;

import org.springframework.util.AntPathMatcher;

import java.util.List;

/**
 * 限流规则匹配（纯逻辑，可单测）。
 *
 * <p><b>最具体优先</b>：多条规则命中同一路径时，取 pattern 最长的一条。
 * 这样可以给「进件」「征信查询」这类敏感路径配更严的额度，
 * 而全局兜底规则仍用宽松额度，不会互相盖掉。</p>
 */
public final class RateLimitRuleMatcher {

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final List<RateLimitProperties.Rule> rules;

    public RateLimitRuleMatcher(List<RateLimitProperties.Rule> rules) {
        this.rules = rules == null ? List.of() : rules;
    }

    /**
     * 命中最具体的一条规则。
     *
     * @param path 请求路径
     * @return 命中的规则；无命中返回 null（不限流）
     */
    public RateLimitProperties.Rule match(String path) {
        if (path == null) {
            return null;
        }
        RateLimitProperties.Rule best = null;
        for (RateLimitProperties.Rule rule : rules) {
            if (rule == null || rule.getPath() == null) {
                continue;
            }
            if (!matcher.match(rule.getPath(), path)) {
                continue;
            }
            if (best == null || rule.getPath().length() > best.getPath().length()) {
                best = rule;
            }
        }
        return best;
    }
}
