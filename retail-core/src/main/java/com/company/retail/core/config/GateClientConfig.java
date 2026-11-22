package com.company.retail.core.config;

import com.company.retail.common.contract.GateClients;
import com.company.retail.common.contract.GateShapeDecoder;
import com.company.retail.core.integration.DisbursementGateChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;

/**
 * 代付门禁装配（G8）：frozen FeignClients（@EnableFeignClients 产出 bean）经
 * {@link GateClients} 访问桥适配成 {@link DisbursementGateChecker.GateClient} 端口。
 * Feign 解码器用 {@link GateShapeDecoder} 绕行冻结契约的内部类缺陷（共享区缺陷已上报）。
 * 单测里用挡板 {@code GateClient} 直接测 fail-closed 语义，不起真依赖进程。
 */
@Configuration
public class GateClientConfig {

    @Bean
    public DisbursementGateChecker disbursementGateChecker(ApplicationContext context,
                                                           ObjectMapper mapper) {
        return new DisbursementGateChecker(
                GateClients.contractGate(context, mapper),
                GateClients.underwritingGate(context, mapper));
    }

    @Bean
    public feign.codec.Decoder feignDecoder(HttpMessageConverters converters) {
        return new GateShapeDecoder(converters);
    }
}
