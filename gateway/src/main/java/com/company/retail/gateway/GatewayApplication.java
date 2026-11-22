package com.company.retail.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 内部网关入口（:18081，R02 §3 范式 2）。
 *
 * <p>精确规则在前、{@code /api/**} 兜底到 retail-core 在后，首个匹配即命中；
 * URI 双模 {@code ${ROUTE_*:lb://服务名}}——本地无 Nacos 时环境变量覆盖为 http 直连。</p>
 * <p><b>网关本阶段不验签</b>：JWT 鉴权下沉各服务本地验签（零信任，R02 §3.1 范式 3）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.company.retail.gateway")
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
