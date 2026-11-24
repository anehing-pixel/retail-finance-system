package com.company.retail.common.contract;

import com.company.retail.common.outbound.OutboundHttpClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 同意门禁 Facade 装配（同包内才能命名 package-private 的 {@code ConsentGateClient}）。
 * 由各业务服务 Application {@code @Import} 引入；只消费冻结契约，不新增契约。
 */
@Configuration
public class W4GateFacadeConfig {

    @Bean
    public ConsentGateFacade consentGateFacade(
            FeignContracts.ConsentGateClient consentGateClient,
            @Value("${retail.clients.customer.url:}") String customerBaseUrl,
            @Value("${retail.auth.secret:}") String staffJwtSecret,
            OutboundHttpClientFactory outbound) {
        // W-SIM：注入员工侧密钥，机器场景（事件消费/日批）自动签系统服务 token
        return new ConsentGateFacade(consentGateClient, customerBaseUrl, staffJwtSecret, outbound);
    }
}
