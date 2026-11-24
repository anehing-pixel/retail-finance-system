package com.company.retail.customer.config;

import com.company.retail.common.outbound.OutboundHttpClientFactory;
import com.company.retail.common.outbound.OutboundProfile;
import com.company.retail.common.security.SecretPolicy;
import com.company.retail.customer.infra.PiiCryptoService;
import com.company.retail.customer.integration.HttpKycProvider;
import com.company.retail.customer.integration.KycProvider;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * customer-service 专属装配：PII 字段加密（密钥独立，R06 §5 三把密钥互不相同）
 * + eKYC 供应商双模（W-SIM2：provider-url 空=无 bean，服务走纯人工 MANUAL）。
 */
@Configuration
@MapperScan("com.company.retail.customer.mapper")
public class CustomerBeansConfig {

    @Bean
    public PiiCryptoService piiCryptoService(
            @Value("${retail.pii.data-key}") String dataKey,
            @Value("${retail.pii.hmac-key}") String hmacKey,
            @Value("${retail.pii.key-version:v1}") String keyVersion,
            Environment environment) {
        // R11 P0-8：无默认值；缺失即启动失败，弱值 / 复用同样 fail-fast（dev profile 例外仅限本地）
        boolean devMode = SecretPolicy.devMode(environment.getActiveProfiles());
        SecretPolicy.requireStrong("retail.pii.data-key（RETAIL_PII_DATA_KEY）", dataKey, devMode);
        SecretPolicy.requireStrong("retail.pii.hmac-key（RETAIL_PII_HMAC_KEY）", hmacKey, devMode);
        SecretPolicy.requireDistinct("retail.pii.data-key", dataKey,
                "retail.pii.hmac-key", hmacKey);
        return new PiiCryptoService(dataKey, hmacKey, keyVersion);
    }

    /** eKYC 供应商（P-12）：url 为空时不注册 bean → IdentityVerificationService 走 ObjectProvider 缺省。 */
    @Bean
    public KycProvider kycProvider(@Value("${retail.kyc.provider-url:}") String providerUrl,
                                   OutboundHttpClientFactory outbound) {
        return (providerUrl == null || providerUrl.isBlank())
                ? null
                : new HttpKycProvider(providerUrl, outbound.client("kyc", OutboundProfile.ADVISORY));
    }
}

