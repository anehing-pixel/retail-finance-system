package com.company.retail.externalapi;

import com.company.platform.exception.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

/**
 * 外部 DMZ 入口（:18091，R02 §3）。
 *
 * <p><b>独立进程独立端口</b>：{@code /external/**} 不经过内部网关——
 * 网络隔离不能靠同一应用加路径。partner 鉴权走 per-partner HMAC + nonce（R07 §5），
 * 不用内部员工/客户 JWT；integration.* 表是其真相源。</p>
 */
@SpringBootApplication(scanBasePackages = "com.company.retail.externalapi")
@EnableDiscoveryClient
@Import(GlobalExceptionHandler.class)
public class ExternalApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExternalApiApplication.class, args);
    }
}
