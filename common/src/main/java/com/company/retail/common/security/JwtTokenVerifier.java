package com.company.retail.common.security;

import com.company.platform.exception.BusinessException;
import com.company.platform.security.SubjectType;
import com.company.platform.security.TokenVerifier;
import com.company.platform.security.VerifiedToken;
import com.company.retail.domain.exception.RFErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 双主体 JWT 验签（机制实现，实现中台 {@link TokenVerifier} 端口；切断点 ③）。
 *
 * <p><b>双主体两套密钥</b>（R00 §6：三把密钥互不相同，员工/客户两套 issuer + audience + 密钥）：</p>
 * <ul>
 *   <li>员工侧：{@code RETAIL_JWT_SECRET}（{@link SubjectType#EMPLOYEE}，带角色与权限点）；</li>
 *   <li>客户侧：{@code RETAIL_CUSTOMER_JWT_SECRET}（{@link SubjectType#CUSTOMER}，无 RBAC）。</li>
 * </ul>
 *
 * <p><b>主体类型在验签层强制</b>：本实例只接受声明了 {@code expectedSubjectType} 的令牌——
 * 员工密钥签的客户 token / 客户密钥签的员工 token / 主体类型声明不符，一律 401。
 * 令牌合法但主体类型不符 ≠ 认证成功：不存在「先过认证再用 @RequireSubject 挡」的窗口。</p>
 */
public class JwtTokenVerifier implements TokenVerifier {

    /** 员工侧实例的配置前缀：retail.auth.* */
    public static final String STAFF_PREFIX = "retail.auth";
    /** 客户侧实例的配置前缀：retail.customer-auth.* */
    public static final String CUSTOMER_PREFIX = "retail.customer-auth";

    private final SecretKey signingKey;
    private final SubjectType expectedSubjectType;

    /**
     * @param secret              HMAC 密钥（至少 32 字节，HS256）
     * @param expectedSubjectType 本实例只接受的主体类型
     */
    public JwtTokenVerifier(String secret, SubjectType expectedSubjectType) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT 密钥至少 32 字节（HS256 弱密钥会被 jjwt 拒绝）");
        }
        this.signingKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.expectedSubjectType = expectedSubjectType;
    }

    @Override
    public VerifiedToken verify(String bearerToken) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(bearerToken)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            // 过期、签名不符、格式错误统一按未认证处理，不向外泄露细节（防枚举/探测）
            throw new BusinessException(RFErrorCode.RF_AUTH_001, Map.of("reason", "BAD_TOKEN"));
        }

        String typeText = claims.get("subjectType", String.class);
        SubjectType actual = parseSubjectType(typeText);
        if (actual != expectedSubjectType) {
            // 主体类型不符：另一套密钥签出来的 token 或声明被篡改——按未认证拒绝（切断点 ③）
            throw new BusinessException(RFErrorCode.RF_AUTH_001, Map.of(
                    "reason", "SUBJECT_TYPE_MISMATCH",
                    "expected", expectedSubjectType.name(),
                    "actual", String.valueOf(typeText)));
        }

        List<String> roles = claims.get("roles", List.class);
        List<String> perms = claims.get("perms", List.class);
        return new VerifiedToken(
                claims.getSubject(),
                actual,
                roles == null ? Set.of() : Set.copyOf(roles),
                perms == null ? Set.of() : perms.stream().map(String::valueOf).collect(Collectors.toSet()),
                claims);
    }

    private SubjectType parseSubjectType(String text) {
        if (text == null) {
            return null;
        }
        try {
            return SubjectType.valueOf(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** 配置绑定辅助：从属性读密钥与有效期（给 auth-service 签发侧复用同一来源）。 */
    public static String secretOf(String secretValue) {
        return secretValue;
    }

    /**
     * 签发辅助（机制层同构）：auth-service 用它造 token；验签端用 {@link #verify}。
     * 保持签发与验签在同一类，避免两侧 claim 键名漂移。
     */
    public String sign(String subject, Map<String, Object> claims, long tokenHours) {
        var now = java.time.Instant.now();
        var builder = Jwts.builder()
                .subject(subject)
                .claim("subjectType", expectedSubjectType.name())
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plusSeconds(tokenHours * 3600)))
                .signWith(signingKey);
        if (claims != null) {
            claims.forEach(builder::claim);
        }
        return builder.compact();
    }
}
