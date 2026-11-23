package com.company.retail.appservice.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * application-service 持久层装配：只注册本服务的 Mapper 包。
 *
 * <p>精确装配纪律（R02 §3.1 范式 1）：{@code @MapperScan} 显式列出单一 mapper 包，
 * 不使用组件全包扫描，也不依赖 starter 的自动扫描。</p>
 */
@Configuration
@MapperScan("com.company.retail.appservice.mapper")
public class ApplicationMyBatisConfig {
}
