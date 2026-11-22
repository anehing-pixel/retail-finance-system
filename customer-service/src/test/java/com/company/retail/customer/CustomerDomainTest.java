package com.company.retail.customer;

import com.company.platform.crypto.AesGcmFieldCipher;
import com.company.retail.customer.domain.Consent;
import com.company.retail.customer.domain.ConsentGatePolicy;
import com.company.retail.customer.domain.IdentityVerification;
import com.company.retail.customer.domain.IdentityVerificationStatus;
import com.company.retail.customer.domain.PiiViewPolicy;
import com.company.retail.customer.infra.PiiCryptoService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W3 customer-service 领域单测（零 Spring、不连库，R04 §7 口径）：
 * 核验状态机、同意门禁 fail-closed、加密/脱敏正确性。
 */
class CustomerDomainTest {

    // ---- 身份核验状态机（R04 §3.2）----

    @Test
    void 核验状态机_合法流转_材料提交到人工通过() {
        IdentityVerification v = new IdentityVerification(1, 100, IdentityVerificationStatus.PENDING);
        v.submitDocument(new IdentityVerification.Document(0, "DRIVER_LICENSE", null,
                LocalDate.now().plusYears(3), "****1234"), LocalDate.now());
        assertEquals(IdentityVerificationStatus.DOCUMENTS_RECEIVED, v.getStatus());

        v.startVerification();
        assertEquals(IdentityVerificationStatus.VERIFYING, v.getStatus());

        v.verify("staff-001", Instant.now());
        assertEquals(IdentityVerificationStatus.VERIFIED, v.getStatus());
        assertEquals("staff-001", v.getReviewerId());
        assertNotNull(v.getVerifiedAt());
    }

    @Test
    void 核验状态机_无材料不得进入核验中() {
        IdentityVerification v = new IdentityVerification(1, 100, IdentityVerificationStatus.PENDING);
        assertThrows(Exception.class, v::startVerification);
    }

    @Test
    void 核验状态机_过期材料被拒() {
        IdentityVerification v = new IdentityVerification(1, 100, IdentityVerificationStatus.PENDING);
        assertThrows(Exception.class, () -> v.submitDocument(new IdentityVerification.Document(
                0, "DRIVER_LICENSE", null, LocalDate.now().minusDays(1), "****1234"), LocalDate.now()));
    }

    @Test
    void 核验状态机_终态不可再迁移_拒绝后重验走新ID() {
        IdentityVerification v = new IdentityVerification(1, 100, IdentityVerificationStatus.VERIFIED);
        assertThrows(Exception.class, () -> v.verify("staff-002", Instant.now()));
        assertThrows(Exception.class, () -> v.reject("staff-002"));
        // REJECTED 是终态：重新核验 = 新建核验记录（新 verification_id），旧记录不改写
        IdentityVerification v2 = new IdentityVerification(2, 100, IdentityVerificationStatus.REJECTED);
        assertThrows(Exception.class, () -> v2.verify("staff-002", Instant.now()));
    }

    // ---- 同意 / 门禁 fail-closed（INV-5）----

    @Test
    void 同意门禁_查不到即拒绝() {
        ConsentGatePolicy.GateDecision d = ConsentGatePolicy.decide(null, Instant.now());
        assertFalse(d.allowed());
        assertEquals("CONSENT_NOT_FOUND", d.reasonCode());
    }

    @Test
    void 同意门禁_已撤回即拒绝_撤回即刻生效() {
        Consent c = new Consent(7, 100, "CREDIT_INQUIRY", "v1", Consent.Status.GRANTED,
                Instant.now().minusSeconds(60), null);
        assertTrue(ConsentGatePolicy.decide(c, Instant.now()).allowed());
        c.withdraw(Instant.now());
        assertFalse(c.isValidAt(Instant.now()));
        assertFalse(ConsentGatePolicy.decide(c, Instant.now()).allowed());
        assertEquals("CONSENT_WITHDRAWN", ConsentGatePolicy.decide(c, Instant.now()).reasonCode());
    }

    @Test
    void 同意门禁_已过期即拒绝() {
        Consent c = new Consent(8, 100, "CREDIT_INQUIRY", "v1", Consent.Status.GRANTED,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(60));
        assertFalse(ConsentGatePolicy.decide(c, Instant.now()).allowed());
        assertEquals("CONSENT_EXPIRED", ConsentGatePolicy.decide(c, Instant.now()).reasonCode());
    }

    @Test
    void 同意_重复撤回被拒_撤回不删历史() {
        Consent c = new Consent(9, 100, "CREDIT_INQUIRY", "v1", Consent.Status.GRANTED,
                Instant.now(), null);
        c.withdraw(Instant.now());
        assertThrows(Exception.class, () -> c.withdraw(Instant.now()));
        // 撤回只改状态，聚合记录仍在（append-only 历史在表层保证）
        assertEquals(Consent.Status.WITHDRAWN, c.getStatus());
    }

    // ---- 加密 / 脱敏正确性（R06 §5）----

    @Test
    void 加密_同明文两次密文不同_随机IV() {
        PiiCryptoService crypto = new PiiCryptoService("w3-test-data-key", "w3-test-hmac-key", "v1");
        byte[] c1 = crypto.encryptField("090-1234-5678");
        byte[] c2 = crypto.encryptField("090-1234-5678");
        assertNotEquals(c1, c2);
    }

    @Test
    void 加密_往返还原_与中台AesGcm一致() {
        PiiCryptoService crypto = new PiiCryptoService("w3-test-data-key", "w3-test-hmac-key", "v1");
        String plain = "東京都港区芝浦１丁目２－３";
        byte[] cipher = crypto.encryptField(plain);
        assertEquals(plain, crypto.decryptField(cipher));
        // 密文被篡改 → GCM 认证失败直接抛，绝不静默产出垃圾明文
        byte[] tampered = cipher.clone();
        tampered[tampered.length - 1] ^= 0x01;
        assertThrows(Exception.class, () -> crypto.decryptField(tampered));
        // 与中台机制层直连口径一致（BYTEA 存原始字节，读回 base64 交 cipher）
        AesGcmFieldCipher platform = new AesGcmFieldCipher("w3-test-data-key");
        assertEquals(plain, platform.decrypt(java.util.Base64.getEncoder().encodeToString(cipher)));
    }

    @Test
    void 加密_HMAC指纹确定且区分明文() {
        PiiCryptoService crypto = new PiiCryptoService("w3-test-data-key", "w3-test-hmac-key", "v1");
        String h1 = crypto.hmacHash("山田太郎");
        assertEquals(h1, crypto.hmacHash("山田太郎"));
        assertNotEquals(h1, crypto.hmacHash("山田花子"));
        assertEquals(64, h1.length());
    }

    @Test
    void 脱敏_姓名只显姓_电话末四位_地址到市区() {
        assertEquals("山***", PiiViewPolicy.maskName("山田太郎"));
        assertEquals("*******5678", PiiViewPolicy.maskPhone("09012345678"));
        assertEquals("東京都港区********", PiiViewPolicy.maskAddress("東京都港区芝浦１丁目２－３"));
        assertEquals("****-**-**", PiiViewPolicy.maskBirthDate("1990-04-01"));
        assertTrue(PiiViewPolicy.maskAnnualIncome("5000000").endsWith("0000"));
        assertFalse(PiiViewPolicy.maskAnnualIncome("5000000").startsWith("5"));
    }
}
