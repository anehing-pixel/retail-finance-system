package com.company.retail.customer.infra;

import com.company.platform.crypto.AesGcmFieldCipher;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/**
 * PII 字段加密服务（W3 落点）：字段级 AES-256-GCM（机制在中台 {@link AesGcmFieldCipher}）+ HMAC-SHA256 检索列。
 *
 * <p>密钥独立 {@code retail.pii.data-key}（R06 §5：与 JWT 密钥、账号加密密钥分离）；
 * 检索密钥 {@code retail.pii.hmac-key} 独立。{@code key_version} 落库支持轮换。
 * BYTEA 存原始密文字节（base64 解码后），读回时再编 base64 交给 cipher。</p>
 */
public class PiiCryptoService {

    private final AesGcmFieldCipher cipher;
    private final byte[] hmacKey;
    private final String keyVersion;

    public PiiCryptoService(String dataKey, String hmacKey, String keyVersion) {
        this.cipher = new AesGcmFieldCipher(dataKey);
        this.hmacKey = hmacKey.getBytes(StandardCharsets.UTF_8);
        this.keyVersion = keyVersion;
    }

    /** 明文 → 密文字节（BYTEA）。null 透传 null。 */
    public byte[] encryptField(String plain) {
        String base64 = cipher.encrypt(plain);
        return base64 == null ? null : Base64.getDecoder().decode(base64);
    }

    /** 密文字节（BYTEA）→ 明文。密文被篡改时 GCM 认证失败直接抛——不静默产出垃圾。 */
    public String decryptField(byte[] cipherBytes) {
        if (cipherBytes == null) {
            return null;
        }
        return cipher.decrypt(Base64.getEncoder().encodeToString(cipherBytes));
    }

    /** 精确检索用 HMAC-SHA256 指纹（64 位 hex，CHAR(64) 列）。null 透传 null。 */
    public String hmacHash(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    public String getKeyVersion() {
        return keyVersion;
    }
}
