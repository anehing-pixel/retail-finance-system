package com.company.retail.core.config;

import com.company.retail.core.integration.BankPayAdapter;
import com.company.retail.core.integration.HttpBankPayAdapter;
import com.company.retail.core.integration.StubBankPayAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 银行代付网关装配（W-SIM2）：双模——{@code retail.core.bank-pay-url}
 * 空 = 内联挡板（W6 原语义），非空 = HTTP 适配器（机构模拟器 / 未来真实银行通道）。
 */
@Configuration
public class BankGatewayConfig {

    @Bean
    public BankPayAdapter bankPayAdapter(
            @Value("${retail.core.bank-pay-url:}") String bankPayUrl) {
        return (bankPayUrl == null || bankPayUrl.isBlank())
                ? new StubBankPayAdapter() : new HttpBankPayAdapter(bankPayUrl);
    }
}
