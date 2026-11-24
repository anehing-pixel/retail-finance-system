package com.company.retail.contract.config;

import com.company.retail.common.outbound.OutboundHttpClientFactory;
import com.company.retail.common.outbound.OutboundProfile;
import com.company.retail.contract.integration.HttpDeliveryChannelAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 合同送达通道装配（W-SIM2，P-14）：{@code retail.contract.delivery-url}
 * 空 = 无 bean（纯 DB 交付留痕，C-08 原语义），非空 = HTTP 送达通道适配器。
 */
@Configuration
public class DeliveryChannelConfig {

    @Bean
    public HttpDeliveryChannelAdapter deliveryChannelAdapter(
            @Value("${retail.contract.delivery-url:}") String deliveryUrl,
            OutboundHttpClientFactory outbound) {
        return (deliveryUrl == null || deliveryUrl.isBlank())
                ? null
                : new HttpDeliveryChannelAdapter(deliveryUrl,
                        outbound.client("delivery", OutboundProfile.ADVISORY));
    }
}
