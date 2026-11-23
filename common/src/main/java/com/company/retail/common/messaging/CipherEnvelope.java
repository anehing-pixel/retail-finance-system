package com.company.retail.common.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 密文信封（ADR-RF-014）：MQ 上流动的**唯一**载荷形状。
 *
 * <p><b>为什么是密文而不是明文</b>：broker 落盘、保留、重投都不可控，消息一旦进入队列
 * 就不再是「进程内的一次函数调用」——明文 PII 进队列 = PII 出了可信边界（B-1 精神）。
 * 因此凡是需要跨进程搬运的 PII 载荷，一律先经 {@link EnvelopeCipher} 加密，
 * MQ 里只允许出现本 record 声明的 8 个键。</p>
 *
 * <p><b>绑定关系</b>：{@code bizType} + {@code bizRef} 既在信封明文部分，也在密文明文内部
 * （{@link AesGcmEnvelopeCipher} 组装），消费端解密后必须校验两者一致——
 * 不一致即拒绝，防止把 A 业务的密文挪到 B 业务上重放（信封不可跨业务重绑）。</p>
 *
 * <p><b>不变量</b>：{@code envelopeId} 全局唯一（幂等键）；{@code ciphertext} 是
 * {@code AesGcmFieldCipher} 的输出（base64，自带 IV），信封内**不含** IV 独立字段
 * ——中台 cipher 自行管理 IV，密钥不出信封。</p>
 *
 * @param envelopeId 信封唯一标识（幂等键，UUID）
 * @param bizType    业务链路类型（{@link EnvelopeTopics} 的链路常量，非 PII）
 * @param bizRef     业务引用键（内部 ID，非 PII）
 * @param keyVersion 数据密钥版本（轮换用，R06 §5）
 * @param alg        加密算法标识（本项目固定 AES-256-GCM）
 * @param ciphertext 密文（base64）
 * @param traceId    链路追踪 ID
 * @param issuedAt   签发时间（ISO-8601，JST）
 */
public record CipherEnvelope(
        String envelopeId,
        String bizType,
        String bizRef,
        String keyVersion,
        String alg,
        String ciphertext,
        String traceId,
        String issuedAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 序列化为 JSON（顶层键固定 8 个，守卫按此白名单校验）。 */
    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("envelopeId", envelopeId);
        m.put("bizType", bizType);
        m.put("bizRef", bizRef);
        m.put("keyVersion", keyVersion);
        m.put("alg", alg);
        m.put("ciphertext", ciphertext);
        m.put("traceId", traceId);
        m.put("issuedAt", issuedAt);
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            throw new MqEnvelopeException("密文信封序列化失败", e);
        }
    }

    /**
     * 从 JSON 解析信封。手工按字段取值（不依赖 record 反序列化器），
     * 缺字段一律抛 {@link MqEnvelopeException}——半截信封进消费端等于噪声。
     */
    public static CipherEnvelope fromJson(String json) {
        JsonNode node;
        try {
            node = MAPPER.readTree(json == null ? "" : json);
        } catch (Exception e) {
            throw new MqEnvelopeException("密文信封不是合法 JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new MqEnvelopeException("密文信封不是 JSON 对象");
        }
        return new CipherEnvelope(
                text(node, "envelopeId"),
                text(node, "bizType"),
                text(node, "bizRef"),
                text(node, "keyVersion"),
                text(node, "alg"),
                text(node, "ciphertext"),
                text(node, "traceId"),
                text(node, "issuedAt"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
}
