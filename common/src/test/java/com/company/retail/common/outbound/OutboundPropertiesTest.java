package com.company.retail.common.outbound;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-7：出站策略默认值（R02 §7.4 / R07 §4.1 基线）。
 */
class OutboundPropertiesTest {

    private final OutboundProperties props = new OutboundProperties();

    @Test
    void defaultsAreEnabled() {
        assertTrue(props.isEnabled());
        assertTrue(props.isCircuitEnabled());
    }

    @Test
    void gateProfileMatchesFailClosedBaseline() {
        OutboundPolicy gate = props.policyOf(OutboundProfile.GATE);
        assertEquals(Duration.ofMillis(500), gate.getConnectTimeout());
        assertEquals(Duration.ofSeconds(2), gate.getReadTimeout());
        assertEquals(2, gate.getMaxAttempts());
        assertEquals(5, gate.getFailureThreshold());
        assertEquals(Duration.ofSeconds(30), gate.getOpenDuration());
    }

    @Test
    void financialProfileNeverRetries() {
        OutboundPolicy financial = props.policyOf(OutboundProfile.FINANCIAL);
        assertEquals(1, financial.getMaxAttempts());
        assertEquals(3, financial.getFailureThreshold());
        assertEquals(Duration.ofSeconds(60), financial.getOpenDuration());
    }

    @Test
    void writeAndForwardProfilesDoNotRetry() {
        assertEquals(1, props.policyOf(OutboundProfile.WRITE).getMaxAttempts());
        assertEquals(1, props.policyOf(OutboundProfile.FORWARD).getMaxAttempts());
    }

    @Test
    void taskRetryDelaysFollowR07Baseline() {
        assertEquals(java.util.List.of(
                        Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
                        Duration.ofHours(2), Duration.ofHours(6)),
                props.getTasks().getRetryDelays());
        assertEquals(5, props.getTasks().getMaxRetries());
        assertTrue(props.getTasks().isEnabled());
    }

    @Test
    void policyOfReturnsTheConfiguredProfileObject() {
        assertSame(props.getGate(), props.policyOf(OutboundProfile.GATE));
        assertSame(props.getAdvisory(), props.policyOf(OutboundProfile.ADVISORY));
    }
}
