package com.company.retail.common.outbound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * P0-7：按 channel 缓存出站客户端，避免每次调用重建连接池/熔断器。
 */
class OutboundHttpClientFactoryTest {

    @Test
    void cachesClientPerChannel() {
        OutboundHttpClientFactory factory = new OutboundHttpClientFactory(new OutboundProperties());

        OutboundHttpClient first = factory.client("cic", OutboundProfile.QUERY);
        OutboundHttpClient second = factory.client("cic", OutboundProfile.QUERY);

        assertSame(first, second);
    }

    @Test
    void differentChannelsUseDifferentClientsAndBreakers() {
        OutboundHttpClientFactory factory = new OutboundHttpClientFactory(new OutboundProperties());

        assertNotSame(factory.client("cic", OutboundProfile.QUERY),
                factory.client("dms", OutboundProfile.ADVISORY));
    }

    @Test
    void defaultsFactoryCanBuildAllProfiles() {
        OutboundHttpClientFactory factory = OutboundHttpClientFactory.defaults();

        for (OutboundProfile profile : OutboundProfile.values()) {
            assertNotNull(factory.client("channel-" + profile.name(), profile));
        }
    }
}
