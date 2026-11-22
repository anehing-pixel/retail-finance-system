package com.company.retail.externalapi.security;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * partner 凭据注册表（W2 开发口径）：per-partner 密钥从配置注入。
 *
 * <p>生产真相源是 {@code integration.partners} / {@code integration.partner_credentials}
 * （secret_ref 指向密钥管理设施、key_version 轮换宽限），随 S3 外部对接接线；
 * W2 不接真实供应商，只把「验签机制」做真——密钥管理是外部事实，不凭空对接（P-11）。</p>
 */
@Component
public class PartnerRegistry {

    /** dev partner 密钥（配置注入；生产走 DB + secret_ref，绝不用配置文件密钥）。 */
    private final Map<String, String> secrets;

    public PartnerRegistry(
            @Value("${retail.integration.partners.DMS-DEV-01.secret:dev-dms-partner-secret-0123456789}")
            String dmsSecret) {
        this.secrets = Map.of("DMS-DEV-01", dmsSecret);
    }

    public boolean exists(String partnerCode) {
        return partnerCode != null && secrets.containsKey(partnerCode);
    }

    public String secretOf(String partnerCode) {
        return secrets.get(partnerCode);
    }

    /** W2 dev scope：验签通过即允许 /external/echo 自证；业务 scope 随 S3 落 R07 §5.2 第 4 关。 */
    public Set<String> scopeOf(String partnerCode) {
        return exists(partnerCode) ? Set.of("echo") : Set.of();
    }

    /** 校验入口：按 §5.2 顺序执行 W2 已落地的两关（时间戳 → 签名）。 */
    public void verify(String method, String path, String partnerCode, String timestamp,
                       String nonce, String keyVersion, String body, String signature) {
        if (!exists(partnerCode)) {
            throw new BusinessException(RFErrorCode.RF_PTNR_003,
                    Map.of("partnerCode", String.valueOf(partnerCode)));
        }
        if (nonce == null || nonce.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_PTNR_002, Map.of("reason", "NO_NONCE"));
        }
        if (!PartnerSignatureVerifier.timestampFresh(timestamp, Instant.now().getEpochSecond())) {
            throw new BusinessException(RFErrorCode.RF_PTNR_001, Map.of("reason", "STALE_TIMESTAMP"));
        }
        String canonical = PartnerSignatureVerifier.canonical(
                method, path, timestamp, nonce, keyVersion == null ? "v1" : keyVersion, body);
        String expected = PartnerSignatureVerifier.expectedSignature(secretOf(partnerCode), canonical);
        if (signature == null || !PartnerSignatureVerifier.constantTimeEquals(expected, signature)) {
            throw new BusinessException(RFErrorCode.RF_PTNR_001, Map.of("reason", "BAD_SIGNATURE"));
        }
    }
}
