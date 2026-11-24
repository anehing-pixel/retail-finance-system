package com.company.retail.common.config;

import com.company.platform.exception.GlobalExceptionHandler;
import com.company.platform.security.PlatformJwtAuthInterceptor;
import com.company.platform.security.SubjectType;
import com.company.retail.common.security.JwtPassthroughInterceptor;
import com.company.retail.common.security.JwtTokenVerifier;
import com.company.retail.common.security.SecretPolicy;
import com.company.retail.domain.exception.RFErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.UUID;

/**
 * Web 与鉴权装配（各服务 {@code @Import} 进来，只拦 /api/**）。
 *
 * <p>三道关顺序（机制在中台 {@link PlatformJwtAuthInterceptor}）：
 * 认证（本类提供的员工侧 {@link JwtTokenVerifier} 验签）→ 主体类型（{@code @RequireSubject}）
 * → 授权（{@code @RequirePermission} 字符串权限点，语义在 domain {@code Permission}）。</p>
 *
 * <p>双主体说明（W2 冻结）：员工侧验签全服务生效；客户侧 verifier 与拦截器在
 * customer-service 落地时接入（W3），机制不变——再建一个 {@code JwtTokenVerifier(secret, CUSTOMER)}
 * + 第二个拦截器实例即可。两个密钥签发的 token 互不通过对方的验签（401），
 * 这已由验签层的主体类型强关保证。</p>
 */
@Configuration
@Import(GlobalExceptionHandler.class)
public class RfSecurityConfig {

    /** /api/health 与 /api/auth/** 免鉴权：健康自证与登录本身不能要求登录态。 */
    private static final String[] PERMIT_ALL = {
            "/api/health", "/api/*/health", "/api/auth/staff/login", "/api/auth/customer/login"};

    private final String staffSecret;
    private final String customerSecret;

    /**
     * 客户自助端点路径段：这些路径挂客户侧拦截器（验 CUSTOMER token），
     * 其余 /api/** 挂员工侧拦截器（S2 遗留收口：客户 token 过员工拦截器 401 的机制性解法）。
     * 可用 retail.security.customer-path-prefixes 覆盖（逗号分隔）。
     */
    private final String[] customerPathPrefixes;

    /**
     * 密钥无默认值（R11 P0-8）：缺失即占位符解析失败，弱值 / 两把相同由
     * {@link SecretPolicy} fail-fast；仅显式激活 dev profile 的本地进程允许 dev 值。
     */
    public RfSecurityConfig(
            @Value("${retail.auth.secret}") String staffSecret,
            @Value("${retail.customer-auth.secret}") String customerSecret,
            @Value("${retail.security.customer-path-prefixes:/api/customer-self/**}") String[] customerPathPrefixes,
            Environment environment) {
        boolean devMode = SecretPolicy.devMode(environment.getActiveProfiles());
        this.staffSecret = SecretPolicy.requireStrong(
                "retail.auth.secret（RETAIL_JWT_SECRET）", staffSecret, devMode);
        this.customerSecret = SecretPolicy.requireStrong(
                "retail.customer-auth.secret（RETAIL_CUSTOMER_JWT_SECRET）", customerSecret, devMode);
        SecretPolicy.requireDistinct(
                "retail.auth.secret（员工 JWT）", this.staffSecret,
                "retail.customer-auth.secret（客户 JWT）", this.customerSecret);
        this.customerPathPrefixes = customerPathPrefixes;
    }

    /**
     * 员工侧验签器。
     */
    @Bean
    public JwtTokenVerifier staffTokenVerifier() {
        return new JwtTokenVerifier(staffSecret, SubjectType.EMPLOYEE);
    }

    /** 客户侧验签器（双主体切断点③的另一半：互拒已由主体类型强关保证）。 */
    @Bean
    public JwtTokenVerifier customerTokenVerifier() {
        return new JwtTokenVerifier(customerSecret, SubjectType.CUSTOMER);
    }

    /** 平台拦截器（员工侧）：三道关机制 + 401/403 语义码走 RF_*。 */
    @Bean
    public PlatformJwtAuthInterceptor staffAuthInterceptor(JwtTokenVerifier staffTokenVerifier,
                                                          ObjectMapper objectMapper) {
        return new PlatformJwtAuthInterceptor(
                staffTokenVerifier, objectMapper, RFErrorCode.RF_AUTH_001, RFErrorCode.RF_AUTH_002);
    }

    /** 平台拦截器（客户侧）：同机制，只挂客户自助路径段。 */
    @Bean
    public PlatformJwtAuthInterceptor customerAuthInterceptor(JwtTokenVerifier customerTokenVerifier,
                                                             ObjectMapper objectMapper) {
        return new PlatformJwtAuthInterceptor(
                customerTokenVerifier, objectMapper, RFErrorCode.RF_AUTH_001, RFErrorCode.RF_AUTH_002);
    }

    /** 密码散列（auth-service 登录用；机制统一，成本因子 10）。 */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** traceId 贯穿过滤器。 */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> reg = new FilterRegistrationBean<>(new TraceIdFilter());
        reg.addUrlPatterns("/api/*", "/external/*");
        reg.setOrder(1);
        return reg;
    }

    /** Feign 透传：JWT + X-Request-Id（注册为 bean，调用方 @EnableFeignClients 自动应用）。 */
    @Bean
    public JwtPassthroughInterceptor jwtPassthroughInterceptor() {
        return new JwtPassthroughInterceptor();
    }

    /** 拦截器注册：/api/** 员工侧全拦；客户自助路径段挂客户侧拦截器（见 customerPathPrefixes）。 */
    @Bean
    public WebMvcConfig rfWebMvcConfig(PlatformJwtAuthInterceptor staffAuthInterceptor,
                                       PlatformJwtAuthInterceptor customerAuthInterceptor) {
        return new WebMvcConfig(staffAuthInterceptor, customerAuthInterceptor, customerPathPrefixes);
    }

    /** 供门禁/控制器统一取 traceId（属性由 TraceIdFilter 放入）。 */
    public static String traceId(org.springframework.web.context.request.RequestAttributes attrs) {
        Object v = attrs == null ? null : attrs.getAttribute(TraceIdFilter.ATTR,
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return v == null ? UUID.randomUUID().toString() : String.valueOf(v);
    }

    /** WebMvc 装配体（独立类便于 auth-service 定制放行清单）。 */
    public static class WebMvcConfig implements WebMvcConfigurer {

        private final PlatformJwtAuthInterceptor staffInterceptor;
        private final PlatformJwtAuthInterceptor customerInterceptor;
        private final String[] customerPathPrefixes;

        public WebMvcConfig(PlatformJwtAuthInterceptor staffInterceptor,
                            PlatformJwtAuthInterceptor customerInterceptor,
                            String[] customerPathPrefixes) {
            this.staffInterceptor = staffInterceptor;
            this.customerInterceptor = customerInterceptor;
            this.customerPathPrefixes = customerPathPrefixes == null || customerPathPrefixes.length == 0
                    ? new String[]{"/api/customer-self/**"} : customerPathPrefixes;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            // 客户自助路径段优先挂客户侧拦截器；员工侧拦截器对其放行（路径互斥，无双挂）
            registry.addInterceptor(customerInterceptor)
                    .addPathPatterns(customerPathPrefixes)
                    .excludePathPatterns(PERMIT_ALL);
            registry.addInterceptor(staffInterceptor)
                    .addPathPatterns("/api/**")
                    .excludePathPatterns(PERMIT_ALL)
                    .excludePathPatterns(customerPathPrefixes);
        }

        @Override
        public void addCorsMappings(CorsRegistry registry) {
            // W2 本地开发口径；生产必须收紧为 partner 白名单（R07 §5.3，floorplan CORS=* 是已知缺口）
            registry.addMapping("/api/**")
                    .allowedOriginPatterns("*")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*");
        }
    }
}
