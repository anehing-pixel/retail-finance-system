package com.company.retail.recon;

import com.company.retail.common.config.RfDataSourceGuardConfig;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.infrastructure.config.RfOutboxConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;

/**
 * INV-1~5 对拍与差异
 *
 * <p><b>精确装配</b>（R02 §3.1 范式 1）：只扫本服务自己的包，application/infrastructure
 * 层的 bean 用 @Import 按「类」注入——四层共享模块的 bean 不进全包扫描。</p>
 */
@SpringBootApplication(scanBasePackages = "com.company.retail.recon")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.company.retail.common.contract")
@Import({RfDataSourceGuardConfig.class, RfSecurityConfig.class, RfOutboxConfig.class,
        com.company.retail.recon.config.ReconServiceConfig.class})
public class ReconServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconServiceApplication.class, args);
    }
}
