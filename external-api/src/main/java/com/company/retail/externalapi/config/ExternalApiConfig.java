package com.company.retail.externalapi.config;

import com.company.retail.common.config.TraceIdFilter;
import com.company.retail.externalapi.security.CallLogStore;
import com.company.retail.externalapi.security.ConfigPartnerDirectory;
import com.company.retail.externalapi.security.DmzGatekeeper;
import com.company.retail.externalapi.security.JdbcCallLogStore;
import com.company.retail.externalapi.security.JdbcNonceStore;
import com.company.retail.externalapi.security.JdbcPartnerDirectory;
import com.company.retail.externalapi.security.NonceStore;
import com.company.retail.externalapi.security.PartnerDirectory;
import com.company.retail.externalapi.security.PartnerRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/**
 * DMZ 装配：traceId 贯穿（复用 common 的 TraceIdFilter；DMZ 不注册 JWT 拦截器——
 * partner 通道按 R07 §5 用 HMAC 验签，不用内部 JWT）。
 *
 * <p>W9-B 三关装配：partner 档案目录按 {@code retail.dmz.registry-mode} 二选一
 * （CONFIG=配置 / DB={@code integration.partners} 表），nonce 与 call_logs 均走
 * {@code integration.*} 表。</p>
 */
@Configuration
@EnableConfigurationProperties({DmzProperties.class, PartnerProperties.class})
public class ExternalApiConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> reg = new FilterRegistrationBean<>(new TraceIdFilter());
        reg.addUrlPatterns("/external/*");
        reg.setOrder(1);
        return reg;
    }

    @Bean
    public Clock dmzClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public PartnerDirectory partnerDirectory(DmzProperties dmz, PartnerProperties partners,
                                             JdbcTemplate jdbc) {
        return "DB".equalsIgnoreCase(dmz.getRegistryMode())
                ? new JdbcPartnerDirectory(jdbc)
                : new ConfigPartnerDirectory(partners);
    }

    @Bean
    public DmzGatekeeper dmzGatekeeper(PartnerRegistry registry, PartnerDirectory directory,
                                       NonceStore nonceStore, CallLogStore callLogStore,
                                       DmzProperties dmz, Clock dmzClock) {
        return new DmzGatekeeper(registry, directory, nonceStore, callLogStore, dmz, dmzClock);
    }
}
