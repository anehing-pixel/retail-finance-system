package com.company.retail.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流故障语义（R11 P0-5）：生产默认 fail-closed；本地 dev 必须显式开启 fail-open。
 */
class RateLimitPropertiesTest {

    @Test
    void failClosedByDefault() {
        assertFalse(new RateLimitProperties().isFailOpen(),
                "生产默认必须 fail-closed：Redis 不可用时拒绝请求，不能静默放行");
    }

    @Test
    void failOpenMustBeExplicitlyEnabled() {
        RateLimitProperties props = new RateLimitProperties();
        props.setFailOpen(true);
        assertTrue(props.isFailOpen());
    }
}
