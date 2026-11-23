package com.company.retail.customer.config;

import com.company.retail.customer.infra.PiiCryptoService;
import com.company.retail.customer.integration.HttpKycProvider;
import com.company.retail.customer.integration.KycProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * customer-service 专属装配：PII 字段加密（密钥独立，R06 §5 三把密钥互不相同）
 * + eKYC 供应商双模（W-SIM2：provider-url 空=无 bean，服务走纯人工 MANUAL）。
 */
@Configuration
public class CustomerBeansConfig {

    @Bean
    public PiiCryptoService piiCryptoService(
            @Value("${retail.pii.data-key:dev-only-retail-pii-data-key-change-me-in-prod-012345}") String dataKey,
            @Value("${retail.pii.hmac-key:dev-only-retail-pii-hmac-key-change-me-in-prod-01234}") String hmacKey,
            @Value("${retail.pii.key-version:v1}") String keyVersion) {
        return new PiiCryptoService(dataKey, hmacKey, keyVersion);
    }

    /** eKYC 供应商（P-12）：url 为空时不注册 bean → IdentityVerificationService 走 ObjectProvider 缺省。 */
    @Bean
    public KycProvider kycProvider(@Value("${retail.kyc.provider-url:}") String providerUrl) {
        return (providerUrl == null || providerUrl.isBlank()) ? null : new HttpKycProvider(providerUrl);
    }
}

