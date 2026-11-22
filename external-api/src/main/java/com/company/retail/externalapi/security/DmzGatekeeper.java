package com.company.retail.externalapi.security;

import com.company.platform.exception.BusinessException;
import com.company.platform.exception.ErrorCode;
import com.company.retail.common.config.TraceIdFilter;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.externalapi.config.DmzProperties;
import com.company.retail.externalapi.security.PartnerDirectory.PartnerProfile;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * DMZ 三关守门人（W9-B，R07 §5.2 校验顺序，不可调换）：
 *
 * <ol>
 *   <li>partner 已登记（未登记 → {@code RF_PTNR_003}，W2 既有口径）；</li>
 *   <li>时间戳 ±300s（→ {@code RF_PTNR_001}）；</li>
 *   <li>nonce 未使用（写 {@code integration.nonces}，重放 → {@code RF_PTNR_002}；
 *       落库前惰性清理过期 nonce）；</li>
 *   <li>签名常量时间比较（→ {@code RF_PTNR_001}）——先于 scope/IP，
 *       避免对伪造请求做后续校验；</li>
 *   <li>partner 状态（DB 模式 DISABLED → {@code RF_PTNR_003}）；</li>
 *   <li>scope（超出 → {@code RF_PTNR_003}）；</li>
 *   <li>IP 白名单（全局开关开且 partner 名单非空而 IP 不在 → {@code RF_PTNR_003}）。</li>
 * </ol>
 *
 * <p>{@link #guard} 包裹业务动作：无论成败均落 {@code integration.call_logs}
 * （result = SUCCESS 或失败语义码，append-only）。所有开关见 {@link DmzProperties}。</p>
 */
public class DmzGatekeeper {

    private final PartnerRegistry registry;
    private final PartnerDirectory directory;
    private final NonceStore nonceStore;
    private final CallLogStore callLogStore;
    private final DmzProperties props;
    private final Clock clock;

    public DmzGatekeeper(PartnerRegistry registry, PartnerDirectory directory,
                         NonceStore nonceStore, CallLogStore callLogStore,
                         DmzProperties props, Clock clock) {
        this.registry = registry;
        this.directory = directory;
        this.nonceStore = nonceStore;
        this.callLogStore = callLogStore;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 三关校验（不落 call_logs——由 {@link #guard} 统一留痕）。
     * 任一关失败抛 {@link BusinessException}，HTTP 状态取自 {@link RFErrorCode} 字典。
     */
    public void verify(HttpServletRequest request, String partnerCode, String timestamp,
                       String nonce, String keyVersion, String body, String signature) {
        PartnerProfile profile = directory.profileOf(partnerCode);
        if (profile == null) {
            throw new BusinessException(RFErrorCode.RF_PTNR_003,
                    java.util.Map.of("partnerCode", String.valueOf(partnerCode), "reason", "UNKNOWN_PARTNER"));
        }

        // 第 1 关：时间戳 ±300s
        if (!PartnerSignatureVerifier.timestampFresh(timestamp, Instant.now(clock).getEpochSecond())) {
            throw new BusinessException(RFErrorCode.RF_PTNR_001, java.util.Map.of("reason", "STALE_TIMESTAMP"));
        }

        // 第 2 关：nonce 防重放（先惰性清理过期项，再原子登记）
        if (nonce == null || nonce.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_PTNR_002, java.util.Map.of("reason", "NO_NONCE"));
        }
        if (props.isNonceEnabled()) {
            nonceStore.purgeBefore(Instant.now(clock).minusSeconds(props.getNonceRetentionSeconds()));
            if (!nonceStore.tryRecord(partnerCode, nonce)) {
                throw new BusinessException(RFErrorCode.RF_PTNR_002, java.util.Map.of("reason", "NONCE_REPLAY"));
            }
        }

        // 第 3 关：签名（常量时间比较；先于 scope/IP，伪造请求到此为止）
        String secret = registry.secretOf(partnerCode);
        if (secret == null) {
            throw new BusinessException(RFErrorCode.RF_PTNR_003, java.util.Map.of("reason", "NO_CREDENTIAL"));
        }
        String canonical = PartnerSignatureVerifier.canonical(
                request.getMethod(), request.getRequestURI(), timestamp, nonce,
                keyVersion == null ? "v1" : keyVersion, body == null ? "" : body);
        String expected = PartnerSignatureVerifier.expectedSignature(secret, canonical);
        if (signature == null || !PartnerSignatureVerifier.constantTimeEquals(expected, signature)) {
            throw new BusinessException(RFErrorCode.RF_PTNR_001, java.util.Map.of("reason", "BAD_SIGNATURE"));
        }

        // 第 4 关：partner 状态 / scope / IP 白名单
        if (!profile.active()) {
            throw new BusinessException(RFErrorCode.RF_PTNR_003, java.util.Map.of("reason", "PARTNER_DISABLED"));
        }
        if (props.isScopeEnabled() && !profile.scopes().contains(routeKeyOf(request.getRequestURI()))) {
            throw new BusinessException(RFErrorCode.RF_PTNR_003,
                    java.util.Map.of("reason", "SCOPE_DENIED", "scope", routeKeyOf(request.getRequestURI())));
        }
        if (props.isIpAllowlistEnabled() && !profile.ipAllowlist().isEmpty()
                && !profile.ipAllowlist().contains(clientIp(request))) {
            throw new BusinessException(RFErrorCode.RF_PTNR_003,
                    java.util.Map.of("reason", "IP_NOT_ALLOWED", "ip", clientIp(request)));
        }
    }

    /**
     * 校验 + 业务动作包裹：call_logs 全量留痕（成功 SUCCESS / 失败记语义码），
     * 异常原样上抛交全局异常处理。
     */
    public Object guard(HttpServletRequest request, String body, Supplier<Object> action) {
        long start = System.currentTimeMillis();
        String partnerCode = request.getHeader("X-Partner-Code");
        String result = "RF_SYS_001";
        int httpStatus = 500;
        try {
            verify(request, partnerCode,
                    request.getHeader("X-Timestamp"),
                    request.getHeader("X-Nonce"),
                    request.getHeader("X-Key-Version"),
                    body,
                    request.getHeader("X-Signature"));
            Object data = action.get();
            httpStatus = 200;
            result = "SUCCESS";
            return data;
        } catch (BusinessException e) {
            httpStatus = e.getErrorCode().getHttpStatus();
            result = e.getErrorCode().getCode();
            throw e;
        } catch (RuntimeException e) {
            httpStatus = 500;
            result = RFErrorCode.RF_SYS_001.getCode();
            throw e;
        } finally {
            try {
                callLogStore.record(partnerCode, request.getRequestURI(), httpStatus,
                        System.currentTimeMillis() - start, result, traceIdOf(request));
            } catch (RuntimeException logFailure) {
                // 留痕失败不改变主流程语义（append-only 观测面），但必须可被发现
                org.slf4j.LoggerFactory.getLogger(DmzGatekeeper.class)
                        .warn("call_logs 留痕失败 endpoint={}", request.getRequestURI(), logFailure);
            }
        }
    }

    /** 路由键：{@code /external/echo} → {@code echo}，{@code /external/dms/applications} → {@code dms_applications}。 */
    public static String routeKeyOf(String path) {
        String p = path != null && path.startsWith("/external/") ? path.substring("/external/".length()) : path;
        return p == null ? "" : p.replace('/', '_');
    }

    /** 客户端 IP：默认取直连 remoteAddr；仅在 trust-forwarded-for 开启时取 XFF 首跳（防伪造绕过白名单）。 */
    String clientIp(HttpServletRequest request) {
        if (props.isTrustForwardedFor()) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private String traceIdOf(HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.ATTR);
        return traceId == null ? null : String.valueOf(traceId);
    }
}
