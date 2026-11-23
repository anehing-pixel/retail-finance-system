package com.company.retail.gateway.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端 IP 解析口径：不可信来源忽略转发头，可信来源才采信。
 */
class ClientIpResolverTest {

    private static MockServerHttpRequest request(String remoteIp, String forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/x");
        builder.remoteAddress(new InetSocketAddress(remoteIp, 12345));
        if (forwardedFor != null) {
            builder.header(ClientIpResolver.X_FORWARDED_FOR, forwardedFor);
        }
        return builder.build();
    }

    @Test
    @DisplayName("未配置可信代理时，X-Forwarded-For 一律忽略")
    void untrustedSourceIgnoresForwardedHeader() {
        ClientIpResolver resolver = new ClientIpResolver("");
        assertFalse(resolver.hasTrustedProxies());
        assertEquals("10.0.0.5", resolver.resolve(request("10.0.0.5", "1.2.3.4")));
    }

    @Test
    @DisplayName("直连来源不在可信网段时仍忽略转发头")
    void sourceOutsideTrustedCidrIgnoresForwardedHeader() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1/32");
        assertEquals("10.0.0.5", resolver.resolve(request("10.0.0.5", "1.2.3.4")));
    }

    @Test
    @DisplayName("直连来源命中可信网段时，采信 X-Forwarded-For 首个非可信地址")
    void trustedProxyUsesForwardedChain() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1/32,10.0.0.0/8");
        assertEquals("203.0.113.9", resolver.resolve(request("127.0.0.1", "203.0.113.9, 10.0.0.1")));
    }

    @Test
    @DisplayName("整条链都是可信代理时取链首地址")
    void allTrustedChainTakesFirst() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");
        assertEquals("10.0.0.7", resolver.resolve(request("10.0.0.1", "10.0.0.7, 10.0.0.1")));
    }

    @Test
    @DisplayName("转发头里的主机名等非 IP 字面量被忽略")
    void nonLiteralForwardedEntryIgnored() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1/32");
        assertEquals("127.0.0.1", resolver.resolve(request("127.0.0.1", "evil.example.com")));
    }

    @Test
    @DisplayName("非法 CIDR 配置被忽略，不影响启动")
    void invalidCidrIgnored() {
        ClientIpResolver resolver = new ClientIpResolver("not-a-cidr, 999.999.999.999/24");
        assertFalse(resolver.hasTrustedProxies());
    }

    @Test
    @DisplayName("IPv6 字面量可解析，且同一地址的不同写法归一到同一桶")
    void ipv6LiteralSupported() {
        ClientIpResolver resolver = new ClientIpResolver("::1/128");
        assertTrue(resolver.hasTrustedProxies());
        String canonical = resolver.resolve(request("::1", null));
        assertEquals("0:0:0:0:0:0:0:1", canonical);
        // 同一地址的两种写法必须落到同一个计数键，否则换写法即可绕过限流
        assertEquals(canonical, resolver.resolve(request("0:0:0:0:0:0:0:1", null)));
    }
}
