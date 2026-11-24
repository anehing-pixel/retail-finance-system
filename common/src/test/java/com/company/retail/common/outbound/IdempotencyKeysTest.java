package com.company.retail.common.outbound;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * P0-7：跨重试稳定幂等键。
 */
class IdempotencyKeysTest {

    @Test
    void sameBusinessPartsProduceSameKey() {
        assertEquals(IdempotencyKeys.of("CIC-INQ", 1001L, 2002L),
                IdempotencyKeys.of("CIC-INQ", 1001L, 2002L));
    }

    @Test
    void differentBusinessPartsProduceDifferentKeys() {
        assertNotEquals(IdempotencyKeys.of("CIC-INQ", 1001L),
                IdempotencyKeys.of("CIC-INQ", 1002L));
    }

    @Test
    void sanitizesSeparatorsAndNonAsciiForHeaderSafety() {
        assertEquals("A-B-C:12", IdempotencyKeys.of("A B/C", 12L));
        assertEquals("NA:X", IdempotencyKeys.of(null, "X"));
        assertEquals("NA:X", IdempotencyKeys.of("  ", "X"));
    }

    @Test
    void truncatesToProtocolLimit() {
        assertEquals(128, IdempotencyKeys.of("x".repeat(300)).length());
    }

    @Test
    void messageIdIsDeterministicUuidDerivedFromKey() {
        String key = "DMS-UW-RESULT:9:APPROVED";
        UUID expected = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, IdempotencyKeys.messageId(key));
        assertEquals(IdempotencyKeys.messageId(key), IdempotencyKeys.messageId(key));
    }
}
