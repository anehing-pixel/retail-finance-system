package com.company.retail.gateway.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 限流键解析（纯逻辑，可单测）。
 *
 * <p><b>为什么用 Authorization 头的哈希而不是 token 里的主体</b>：网关本阶段<b>不验签</b>
 * （鉴权下沉各服务本地验签，零信任），网关拿不到可信主体。
 * 若直接解 token 会把「未验签的内容」当身份用。
 * 这里的做法是：把 Authorization 头整体哈希当作调用方指纹（区分不同调用方），
 * 无 Authorization 头时退化为来源 IP。</p>
 *
 * <p><b>生产口径</b>：网关验签后应改用验签得到的主体 ID 作键，
 * 比哈希更稳定（同一主体换 token 不会换桶）。</p>
 */
public final class RateLimitKeyResolver {

    private RateLimitKeyResolver() {
    }

    /**
     * @param authorizationHeader Authorization 头（可空）
     * @param remoteAddress       来源地址（可空）
     * @param keyBy               {@code auth_or_ip} 或 {@code ip}
     */
    public static String resolve(String authorizationHeader, String remoteAddress, String keyBy) {
        if (!"ip".equals(keyBy) && authorizationHeader != null && !authorizationHeader.isBlank()) {
            return "a:" + sha256Hex(authorizationHeader);
        }
        return "ip:" + (remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress);
    }

    /** SHA-256 十六进制摘要（不可逆，不把凭据本身写进 Redis 键）。 */
    static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
