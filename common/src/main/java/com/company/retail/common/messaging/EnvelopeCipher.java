package com.company.retail.common.messaging;

/**
 * 密文信封加解密端口（机制件，ADR-RF-014）。
 *
 * <p>实现见 {@link AesGcmEnvelopeCipher}（复用中台 {@code AesGcmFieldCipher}）。
 * 密钥与 PII 字段加密**同一把数据密钥**（R06 §5：不新增密钥面），
 * 由配置 {@code retail.mq.data-key} 注入，未配置时回落到 {@code retail.pii.data-key}。</p>
 */
public interface EnvelopeCipher {

    /**
     * 封装：业务载荷明文 → 密文信封。
     *
     * @param bizType    业务链路类型（{@link EnvelopeTopics} 常量）
     * @param bizRef     业务引用键（内部 ID，非 PII）
     * @param payloadJson 业务载荷 JSON 文本（**允许含 PII**，因为它只在密文内部出现）
     * @param traceId    链路追踪 ID
     */
    CipherEnvelope seal(String bizType, String bizRef, String payloadJson, String traceId);

    /**
     * 解封：密文信封 → 业务载荷明文 JSON。
     *
     * <p>实现必须校验「信封声明的 bizType/bizRef」与「密文内部绑定的 bizType/bizRef」一致，
     * 不一致一律拒绝（防跨业务重放）。</p>
     */
    String open(CipherEnvelope envelope);
}
