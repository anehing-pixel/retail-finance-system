package com.company.retail.common.config;

import com.company.platform.exception.GlobalExceptionHandler;
import com.company.platform.security.PlatformJwtAuthInterceptor;
import com.company.platform.security.SubjectType;
import com.company.retail.common.security.JwtPassthroughInterceptor;
import com.company.retail.common.security.JwtTokenVerifier;
import com.company.retail.domain.exception.RFErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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

    @Value("${retail.auth.secret:dev-only-retail-staff-secret-change-me-in-prod-0123456789}")
    private String staffSecret;

    /**
     * 员工侧验签器。客户侧同构实例由 customer-service 在 W3 提供
     * （{@code new JwtTokenVerifier(customerSecret, SubjectType.CUSTOMER)}）。
     */
    @Bean
    public JwtTokenVerifier staffTokenVerifier() {
        return new JwtTokenVerifier(staffSecret, SubjectType.EMPLOYEE);
    }

    /** 平台拦截器（员工侧）：三道关机制 + 401/403 语义码走 RF_*。 */
    @Bean
    public PlatformJwtAuthInterceptor staffAuthInterceptor(JwtTokenVerifier staffTokenVerifier,
                                                          ObjectMapper objectMapper) {
        return new PlatformJwtAuthInterceptor(
                staffTokenVerifier, objectMapper, RFErrorCode.RF_AUTH_001, RFErrorCode.RF_AUTH_002);
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

    /** 拦截器注册：/api/** 全拦，登录与健康端点放行（见 PERMIT_ALL）。 */
    @Bean
    public WebMvcConfig rfWebMvcConfig(PlatformJwtAuthInterceptor staffAuthInterceptor) {
        return new WebMvcConfig(staffAuthInterceptor);
    }

    /** 供门禁/控制器统一取 traceId（属性由 TraceIdFilter 放入）。 */
    public static String traceId(org.springframework.web.context.request.RequestAttributes attrs) {
        Object v = attrs == null ? null : attrs.getAttribute(TraceIdFilter.ATTR,
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return v == null ? UUID.randomUUID().toString() : String.valueOf(v);
    }

    /** WebMvc 装配体（独立类便于 auth-service 定制放行清单）。 */
    public static class WebMvcConfig implements WebMvcConfigurer {

        private final PlatformJwtAuthInterceptor interceptor;

        public WebMvcConfig(PlatformJwtAuthInterceptor interceptor) {
            this.interceptor = interceptor;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(interceptor)
                    .addPathPatterns("/api/**")
                    .excludePathPatterns(PERMIT_ALL);
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
