package com.company.retail.core;

import com.company.retail.common.cache.RfCacheConfig;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.infrastructure.config.MqEnvelopeConfig;
import com.company.retail.infrastructure.config.RfOutboxConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;

/**
 * 核心账务（不拆）：债权、还款计划、代付、入金核销、结清；唯一跑 Flyway
 *
 * <p><b>精确装配</b>（R02 §3.1 范式 1）：只扫本服务自己的包，application/infrastructure
 * 层的 bean 用 @Import 按「类」注入——四层共享模块的 bean 不进全包扫描。</p>
 */
@SpringBootApplication(scanBasePackages = "com.company.retail.core")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.company.retail.common.contract")
@Import({RfSecurityConfig.class, RfOutboxConfig.class, RfCacheConfig.class, MqEnvelopeConfig.class,
        com.company.retail.common.outbound.RfFeignConfig.class})
public class CoreServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreServiceApplication.class, args);
    }
}
