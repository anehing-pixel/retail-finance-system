package com.company.retail.common.contract;

import com.company.retail.common.outbound.OutboundHttpClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * servicing 侧同意门禁 Facade 装配（W7）。
 *
 * <p>与 W4 的 {@code W4GateFacadeConfig} 同构：冻结契约
 * {@code FeignContracts.ConsentGateClient} 是 package-private，同包才能命名；
 * 类名与 W4 实现错开（ServicingConsentGate / ServicingGateConfig），避免跨模块 FQCN 冲突。
 * 只<b>消费</b>冻结契约，不新增契约。</p>
 */
@Configuration
public class ServicingGateConfig {

    @Bean
    public ServicingConsentGate servicingConsentGate(
            FeignContracts.ConsentGateClient consentGateClient,
            @Value("${retail.clients.customer.url:}") String customerBaseUrl,
            OutboundHttpClientFactory outbound) {
        return new ServicingConsentGate(consentGateClient, customerBaseUrl, outbound);
    }
}
