package com.company.retail.common.config;

import com.company.retail.common.security.DataSourceRolePolicy;
import com.company.retail.common.security.SecretPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 运行期数据源启动门禁（R11 P0-1）：各连库服务 {@code @Import} 本类。
 *
 * <p>连接池账号必须是三个受限角色之一，禁止 {@code postgres} 超管；
 * 口令缺失、过短或非 dev profile 的 {@code *_dev} 弱口令一律启动失败。
 * Flyway 迁移账号单独配置（G-DB-3），不经过本门禁。</p>
 */
@Configuration
public class RfDataSourceGuardConfig {

    public RfDataSourceGuardConfig(
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            Environment environment) {
        DataSourceRolePolicy.requireUsername("spring.datasource.username", username);
        DataSourceRolePolicy.requirePassword("spring.datasource.password", password,
                SecretPolicy.devMode(environment.getActiveProfiles()));
    }
}
