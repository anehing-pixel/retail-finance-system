package com.company.retail.common.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 密文信封载荷守卫（INV-4 在 MQ 通道上的对应执行点，ADR-RF-014 §3）。
 *
 * <p><b>默认拒绝</b>：信封 JSON 的顶层键必须全部落在 {@link #ALLOWED_KEYS} 内；
 * 命中 {@link #FORBIDDEN_KEYS}（明文 PII 键）一律拒绝投递。</p>
 *
 * <p><b>只检查顶层键</b>：与 {@code EventPayloadGuard} 同口径——白名单语义是
 * 「顶层键全部显式申报」；嵌套结构不是信封的合法形状。</p>
 *
 * <p><b>为什么信封里没有 payload 键</b>：信封只带密文；明文载荷一旦出现在信封顶层，
 * 就等于把 PII 直接写进 broker 落盘——这是本守卫要拦的头号事故形态。</p>
 */
public final class EnvelopePayloadGuard {

    /** 允许键白名单：信封的全部合法顶层键。 */
    public static final Set<String> ALLOWED_KEYS = Set.of(
            "envelopeId", "bizType", "bizRef", "keyVersion", "alg", "ciphertext", "traceId", "issuedAt");

    /**
     * 禁止键（明文 PII / 明文载荷）。命中即拒绝——宁可漏发一条消息，不可把 PII 写进 broker。
     * 键名沿用 {@code EventPayloadGuard} 的口径（姓名 / 住址 / 生日 / 证件 / 账号 / 收入）。
     */
    public static final Set<String> FORBIDDEN_KEYS = Set.of(
            "payload", "payloadJson", "plain", "plaintext", "data", "body", "content",
            "name", "fullName", "customerName", "ownerName", "address", "birthDate",
            "idNumber", "idCard", "phone", "email", "income", "bankAccount", "accountNo",
            "salary", "maskedNumber", "vin");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EnvelopePayloadGuard() {
    }

    /**
     * 校验信封 JSON；非法即抛 {@link IllegalArgumentException}，消息不投递。
     *
     * @param json 信封 JSON 文本
     */
    public static void validate(String json) {
        JsonNode node;
        try {
            node = MAPPER.readTree(json == null ? "" : json);
        } catch (Exception e) {
            throw new IllegalArgumentException("密文信封不是合法 JSON");
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("密文信封不是 JSON 对象");
        }
        List<String> illegal = new ArrayList<>();
        List<String> piiHits = new ArrayList<>();
        node.fieldNames().forEachRemaining(key -> {
            if (!ALLOWED_KEYS.contains(key)) {
                illegal.add(key);
                if (FORBIDDEN_KEYS.contains(key)) {
                    piiHits.add(key);
                }
            }
        });
        // 明文 PII / 明文载荷键单独报出：这类键一旦出现，说明调用方把「本该加密的载荷」
        // 直接摊在了信封顶层——是本守卫要拦的头号事故形态，报错必须一眼可辨。
        if (!piiHits.isEmpty()) {
            throw new IllegalArgumentException(
                    "密文信封含明文 PII / 明文载荷键（ADR-RF-014 §3 默认拒绝），拒绝投递 piiKeys=" + piiHits);
        }
        if (!illegal.isEmpty()) {
            throw new IllegalArgumentException(
                    "密文信封含白名单外的键（ADR-RF-014 §3 默认拒绝），拒绝投递 illegalKeys=" + illegal);
        }
        for (String required : new String[]{"envelopeId", "bizType", "bizRef", "ciphertext"}) {
            String v = node.path(required).asText("");
            if (v.isBlank()) {
                throw new IllegalArgumentException("密文信封缺少必填键：" + required);
            }
        }
        String ciphertext = node.path("ciphertext").asText("");
        if (!ciphertext.matches("^[A-Za-z0-9+/]+={0,2}$")) {
            throw new IllegalArgumentException("密文信封的 ciphertext 不是 base64，拒绝投递");
        }
    }
}
