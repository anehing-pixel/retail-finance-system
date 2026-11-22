# -*- coding: utf-8 -*-
"""S1-W2 生成器：10 个业务服务骨架（pom + 启动类 + 健康自证端点 + application.yml）。

服务清单与端口唯一来源：R00 §3.1 / R02 §3。
包名映射（R00 §3.2 的目录名带连字符不能做 Java 包，按 floorplan 范式取短名）：
  retail-core→core  auth-service→auth  customer-service→customer
  application-service→appservice  underwriting-service→underwriting
  bureau-service→bureau  contract-service→contract  title-release-service→titlerelease
  servicing-service→servicing  recon-service→recon
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # 仓根目录

# (目录, artifact, spring-name, 包名, App类名, 端口, 健康段, whoami段, flyway, 是否核心)
SERVICES = [
    ("retail-core", "retail-core", "retail-core", "core", "CoreServiceApplication",
     8090, "/api/health", "/api/whoami", True, True),
    ("auth-service", "retail-auth-service", "auth-service", "auth", "AuthServiceApplication",
     8091, "/api/auth/health", None, False, False),
    ("customer-service", "retail-customer-service", "customer-service", "customer", "CustomerServiceApplication",
     8092, "/api/customers/health", "/api/customers/whoami", False, False),
    ("application-service", "retail-application-service", "application-service", "appservice", "ApplicationServiceApplication",
     8093, "/api/applications/health", "/api/applications/whoami", False, False),
    ("underwriting-service", "retail-underwriting-service", "underwriting-service", "underwriting", "UnderwritingServiceApplication",
     8094, "/api/underwriting/health", "/api/underwriting/whoami", False, False),
    ("bureau-service", "retail-bureau-service", "bureau-service", "bureau", "BureauServiceApplication",
     8095, "/api/credit-inquiries/health", "/api/credit-inquiries/whoami", False, False),
    ("contract-service", "retail-contract-service", "contract-service", "contract", "ContractServiceApplication",
     8096, "/api/contracts/health", "/api/contracts/whoami", False, False),
    ("title-release-service", "retail-title-release-service", "title-release-service", "titlerelease", "TitleReleaseServiceApplication",
     8097, "/api/title-retentions/health", "/api/title-retentions/whoami", False, False),
    ("servicing-service", "retail-servicing-service", "servicing-service", "servicing", "ServicingServiceApplication",
     8098, "/api/collection-cases/health", "/api/collection-cases/whoami", False, False),
    ("recon-service", "retail-recon-service", "recon-service", "recon", "ReconServiceApplication",
     8099, "/api/recon/health", "/api/recon/whoami", False, False),
]

DESC = {
    "retail-core": "核心账务（不拆）：债权、还款计划、代付、入金核销、结清；唯一跑 Flyway",
    "auth-service": "双主体 JWT：员工/客户两套密钥签发与验签（切断点 ③）",
    "customer-service": "个人信息金库：自然人主数据、字段级加密、身份核验、同意",
    "application-service": "申请受理 + DMS 代办 + 销售店主数据唯一投影",
    "underwriting-service": "审查授信、支付能力评估框架、职责分离",
    "bureau-service": "征信机构防腐层：查询/报送/异议申诉",
    "contract-service": "合同与法定书面文件交付留痕",
    "title-release-service": "所有权保留与解除八步、外部登记回执",
    "servicing-service": "贷后逾期天数、催收、征信报送触发",
    "recon-service": "INV-1~5 对拍与差异",
}

POM_TMPL = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.company</groupId>
    <artifactId>retail-finance-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>{artifact}</artifactId>
  <name>Retail Finance :: {name}</name>
  <description>{desc}</description>

  <dependencies>
    <!-- 共用 Web/鉴权基础设施 + 应用/基础设施层（精确装配，不扫全包） -->
    <dependency>
      <groupId>com.company</groupId>
      <artifactId>retail-common</artifactId>
      <version>${{project.version}}</version>
    </dependency>
    <dependency>
      <groupId>com.company</groupId>
      <artifactId>retail-application</artifactId>
      <version>${{project.version}}</version>
    </dependency>
    <dependency>
      <groupId>com.company</groupId>
      <artifactId>retail-infrastructure</artifactId>
      <version>${{project.version}}</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <!-- 服务注册发现；本地无 Nacos 时 fail-fast=false，路由用 ROUTE_* 直连覆盖 -->
    <dependency>
      <groupId>com.alibaba.cloud</groupId>
      <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration>
          <mainClass>com.company.retail.{pkg}.{app}</mainClass>
        </configuration>
        <!-- 自定义 parent（非 spring-boot-starter-parent）需显式绑定 repackage -->
        <executions>
          <execution>
            <goals>
              <goal>repackage</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
"""

APP_TMPL = """package com.company.retail.{pkg};

import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.infrastructure.config.RfOutboxConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;

/**
 * {desc}
 *
 * <p><b>精确装配</b>（R02 §3.1 范式 1）：只扫本服务自己的包，application/infrastructure
 * 层的 bean 用 @Import 按「类」注入——四层共享模块的 bean 不进全包扫描。</p>
 */
@SpringBootApplication(scanBasePackages = "com.company.retail.{pkg}")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.company.retail.common.contract")
@Import({{RfSecurityConfig.class{extra_imports}}})
public class {app} {{

    public static void main(String[] args) {{
        SpringApplication.run({app}.class, args);
    }}
}}
"""

HEALTH_TMPL = """package com.company.retail.{pkg};

import com.company.retail.common.web.WhoamiSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 骨架自证端点（W2）：health 免鉴权，whoami 过 JWT 三道关回显验签结果。
 *
 * <p>「不建只有启动类的空服务」（R02 §3.2）：走网关 + 过拦截器 + 统一响应 + traceId
 * 这条链路可被冒烟真实断言，业务用例自 S2 起按 R05 落入本服务。</p>
 */
@RestController
public class HealthController {{

    {health_mapping}
    public Object health() {{
        return WhoamiSupport.health("{name}");
    }}
{whoami_method}}}
"""

YML_TMPL = """server:
  port: {port}

spring:
  application:
    name: {name}
  datasource:
    # 第一版共库（ADR-RF-009 / R02 §3.1 范式 4）：core | pii | integration | audit 按 schema 分区
    url: ${{SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5435/retail_finance}}
    username: ${{SPRING_DATASOURCE_USERNAME:postgres}}
    password: ${{SPRING_DATASOURCE_PASSWORD:postgres}}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
  flyway:
    enabled: {flyway}{flyway_extra}
  jackson:
    time-zone: Asia/Tokyo
    default-property-inclusion: non_null
  cloud:
    nacos:
      discovery:
        server-addr: ${{NACOS_ADDR:localhost:8848}}
        fail-fast: false

retail:
  auth:
    # 员工侧 JWT 密钥：生产必须环境变量注入，dev 兜底值绝不进生产
    secret: ${{RETAIL_JWT_SECRET:dev-only-retail-staff-secret-change-me-in-prod-0123456789}}
    token-hours: ${{RETAIL_TOKEN_HOURS:8}}
  customer-auth:
    # 客户侧独立密钥（三把密钥互不相同，R00 §6）；验签器 W3 接入 customer-service
    secret: ${{RETAIL_CUSTOMER_JWT_SECRET:dev-only-retail-customer-secret-change-me-in-prod-01234}}
  clients:
    # Feign 门禁双模 URI（R09 §7.2）：空串回退 lb://，本地无注册中心用环境变量直连
    contract:
      url: ${{RETAIL_CLIENTS_CONTRACT_URL:}}
    customer:
      url: ${{RETAIL_CLIENTS_CUSTOMER_URL:}}
    underwriting:
      url: ${{RETAIL_CLIENTS_UNDERWRITING_URL:}}
"""


def write(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)
    print("wrote", path)


for (mdir, artifact, name, pkg, app, port, health_seg, whoami_seg, flyway, is_core) in SERVICES:
    base = os.path.join(ROOT, mdir)
    write(os.path.join(base, "pom.xml"),
          POM_TMPL.format(artifact=artifact, name=name, desc=DESC[mdir], pkg=pkg, app=app))

    extra_imports = ", RfOutboxConfig.class" if is_core else ""
    # auth-service 不引 outbox（不发布事件，消费者 S2 起才有）；retail-core 引 outbox + Flyway
    if mdir == "auth-service":
        extra_imports = ""
    write(os.path.join(base, f"src/main/java/com/company/retail/{pkg}/{app}.java"),
          APP_TMPL.format(pkg=pkg, app=app, desc=DESC[mdir], extra_imports=extra_imports))

    health_mapping = f"@GetMapping(\"{health_seg}\")"
    whoami_method = ""
    if whoami_seg:
        whoami_method = f"""
    @GetMapping("{whoami_seg}")
    public Object whoami() {{
        return WhoamiSupport.whoami("{name}");
    }}
"""
    write(os.path.join(base, f"src/main/java/com/company/retail/{pkg}/HealthController.java"),
          HEALTH_TMPL.format(pkg=pkg, name=name, health_mapping=health_mapping, whoami_method=whoami_method))

    flyway_extra = ""
    if is_core:
        flyway_extra = "\n    # 全仓只有 retail-core 跑 Flyway（R00 §7）；迁移脚本在 retail-infrastructure jar 内\n    locations: classpath:db/migration"
    write(os.path.join(base, "src/main/resources/application.yml"),
          YML_TMPL.format(port=port, name=name,
                          flyway="true" if flyway else "false",
                          flyway_extra=flyway_extra))

print("done")
