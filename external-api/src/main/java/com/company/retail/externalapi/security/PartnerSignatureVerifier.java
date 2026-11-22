package com.company.retail.externalapi.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * partner 签名校验（机制层，R07 §5.1 规范化串 + §5.2 校验顺序）。
 *
 * <pre>
 * canonical = METHOD + "\n" + PATH + "\n" + timestamp + "\n" + nonce + "\n"
 *           + key_version + "\n" + SHA256(body)
 * signature = Base64(HMAC-SHA256(partner_secret, canonical))
 * </pre>
 *
 * <p>相对 floorplan {@code PaymentWebhookVerifier} 的四点强化：① METHOD/PATH/SHA256(body)
 * 全进签名串；② nonce 防重放（W2 纯函数版；DB 落 {@code integration.nonces} 随 S3 接线）；
 * ③ per-partner 密钥 + key_version 轮换宽限；④ 独立进程独立端口。</p>
 *
 * <p>校验顺序（R07 §5.2，不可调换）：时间戳 ±300s → nonce → 签名（常量时间比较）→
 * partner 状态/scope/IP → 幂等。W2 先落 1 与 3（纯函数可测），2/4/5 在 integration 表接线时补齐。</p>
 */
public final class PartnerSignatureVerifier {

    /** 时间戳容差：±300 秒（R07 §5.2）。 */
    public static final long MAX_CLOCK_SKEW_SECONDS = 300;

    private PartnerSignatureVerifier() {
    }

    /** 构造规范化串（供签名方与验签方共用，两侧不得各写一套）。 */
    public static String canonical(String method, String path, String timestamp,
                                   String nonce, String keyVersion, String body) {
        return method + "\n"
                + path + "\n"
                + timestamp + "\n"
                + nonce + "\n"
                + keyVersion + "\n"
                + sha256Hex(body == null ? "" : body);
    }

    /** 按规范化串算期望签名。 */
    public static String expectedSignature(String partnerSecret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(partnerSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    /** 时间戳 ±300s 校验；非法格式一律失败。 */
    public static boolean timestampFresh(String timestamp, long nowEpochSeconds) {
        try {
            long ts = Long.parseLong(timestamp.trim());
            return Math.abs(nowEpochSeconds - ts) <= MAX_CLOCK_SKEW_SECONDS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 常量时间比较：不等长也恒定耗时，防逐字节探测。 */
    public static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String body) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
