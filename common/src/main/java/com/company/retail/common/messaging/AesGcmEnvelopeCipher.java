package com.company.retail.common.messaging;

import com.company.platform.crypto.AesGcmFieldCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 密文信封加解密实现：中台 {@link AesGcmFieldCipher}（AES-256-GCM，密钥独立于 JWT）。
 *
 * <p><b>绑定方式</b>：中台 cipher 不支持 AAD 参数，因此把 {@code bizType} / {@code bizRef}
 * 一并写进被加密的明文里（{@code payloadJson} 作为字符串原样嵌入，不做二次序列化，
 * 避免数值/字段顺序在往返中被改写）。消费端解密后校验三者一致，不一致即拒绝。</p>
 *
 * <p><b>为什么不用 AAD</b>：中台机制已冻结（AesGcmFieldCipher 只暴露 encrypt/decrypt），
 * 本项目不自造第二套加密实现（R03 机制层共享）；明文内绑定等效达到防串改目的。</p>
 */
public final class AesGcmEnvelopeCipher implements EnvelopeCipher {

    /** 算法标识（写入信封，供轮换期识别历史信封）。 */
    public static final String ALG = "AES-256-GCM";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AesGcmFieldCipher cipher;
    private final String keyVersion;

    public AesGcmEnvelopeCipher(String dataKey, String keyVersion) {
        if (dataKey == null || dataKey.isBlank()) {
            throw new IllegalArgumentException("信封数据密钥为空：密文信封不允许无密钥投递");
        }
        this.cipher = new AesGcmFieldCipher(dataKey);
        this.keyVersion = keyVersion == null || keyVersion.isBlank() ? "v1" : keyVersion;
    }

    @Override
    public CipherEnvelope seal(String bizType, String bizRef, String payloadJson, String traceId) {
        if (bizType == null || bizType.isBlank()) {
            throw new MqEnvelopeException("信封缺少 bizType");
        }
        if (bizRef == null || bizRef.isBlank()) {
            throw new MqEnvelopeException("信封缺少 bizRef");
        }
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("bizType", bizType);
        plain.put("bizRef", bizRef);
        plain.put("payloadJson", payloadJson == null ? "" : payloadJson);
        String ciphertext;
        try {
            ciphertext = cipher.encrypt(MAPPER.writeValueAsString(plain));
        } catch (Exception e) {
            throw new MqEnvelopeException("信封加密失败", e);
        }
        return new CipherEnvelope(
                UUID.randomUUID().toString(),
                bizType,
                bizRef,
                keyVersion,
                ALG,
                ciphertext,
                traceId == null ? "" : traceId,
                java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Tokyo")).toString());
    }

    @Override
    public String open(CipherEnvelope envelope) {
        if (envelope == null || envelope.ciphertext() == null || envelope.ciphertext().isBlank()) {
            throw new MqEnvelopeException("信封缺少密文");
        }
        String plain;
        try {
            plain = cipher.decrypt(envelope.ciphertext());
        } catch (Exception e) {
            // 密文被篡改 / 密钥不对：GCM 认证失败——不静默产出垃圾
            throw new MqEnvelopeException("信封解密失败（密文被篡改或密钥不匹配）", e);
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(plain == null ? "" : plain);
        } catch (Exception e) {
            throw new MqEnvelopeException("信封明文不是合法 JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new MqEnvelopeException("信封明文不是 JSON 对象");
        }
        String boundType = node.path("bizType").asText("");
        String boundRef = node.path("bizRef").asText("");
        if (!boundType.equals(envelope.bizType()) || !boundRef.equals(envelope.bizRef())) {
            throw new MqEnvelopeException("信封业务键与密文绑定不一致，拒绝跨业务重放：envelope="
                    + envelope.bizType() + "/" + envelope.bizRef()
                    + " bound=" + boundType + "/" + boundRef);
        }
        return node.path("payloadJson").asText("");
    }

    public String getKeyVersion() {
        return keyVersion;
    }
}
