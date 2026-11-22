package com.company.retail.externalapi.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DMZ 验签机制单测（R07 §5.1 规范化串 / §5.2 校验顺序的纯函数部分）。
 */
class PartnerSignatureVerifierTest {

    private static final String SECRET = "partner-secret-0123456789abcdef0123456789";

    @Test
    void canonicalIncludesMethodPathTimestampNonceKeyVersionBodyHash() {
        String c1 = PartnerSignatureVerifier.canonical("POST", "/external/echo",
                "1700000000", "n-1", "v1", "{\"a\":1}");
        String c2 = PartnerSignatureVerifier.canonical("POST", "/external/echo",
                "1700000000", "n-1", "v1", "{\"a\":1}");
        String c3 = PartnerSignatureVerifier.canonical("GET", "/external/echo",
                "1700000000", "n-1", "v1", "{\"a\":1}");
        assertEquals(c1, c2, "同输入必须产出同规范化串");
        assertNotEquals(c1, c3, "METHOD 必须参与签名串");
        assertTrue(c1.split("\n").length == 6, "六个组成部分");
    }

    @Test
    void bodyChangeChangesSignature() {
        String sig1 = PartnerSignatureVerifier.expectedSignature(SECRET,
                PartnerSignatureVerifier.canonical("POST", "/p", "1700000000", "n", "v1", "a"));
        String sig2 = PartnerSignatureVerifier.expectedSignature(SECRET,
                PartnerSignatureVerifier.canonical("POST", "/p", "1700000000", "n", "v1", "b"));
        assertNotEquals(sig1, sig2, "body 摘要必须参与签名串");
    }

    @Test
    void validSignaturePassesConstantTimeCompare() {
        String canonical = PartnerSignatureVerifier.canonical("POST", "/external/echo",
                "1700000000", "nonce-9", "v1", "hello");
        String signature = PartnerSignatureVerifier.expectedSignature(SECRET, canonical);
        assertTrue(PartnerSignatureVerifier.constantTimeEquals(signature, signature));
        assertFalse(PartnerSignatureVerifier.constantTimeEquals(signature,
                PartnerSignatureVerifier.expectedSignature("wrong-secret", canonical)));
    }

    @Test
    void timestampWindowIsPlusMinus300Seconds() {
        long now = 1_800_000_000L;
        assertTrue(PartnerSignatureVerifier.timestampFresh(String.valueOf(now), now));
        assertTrue(PartnerSignatureVerifier.timestampFresh(String.valueOf(now - 299), now));
        assertTrue(PartnerSignatureVerifier.timestampFresh(String.valueOf(now + 300), now));
        assertFalse(PartnerSignatureVerifier.timestampFresh(String.valueOf(now - 301), now));
        assertFalse(PartnerSignatureVerifier.timestampFresh("not-a-number", now));
        assertFalse(PartnerSignatureVerifier.timestampFresh("", now));
    }
}
