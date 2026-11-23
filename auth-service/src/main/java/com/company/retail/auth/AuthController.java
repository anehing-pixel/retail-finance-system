package com.company.retail.auth.controller;

import com.company.platform.exception.BusinessException;
import com.company.platform.response.ApiResponse;
import com.company.platform.security.SubjectType;
import com.company.retail.auth.RolePermissions;
import com.company.retail.auth.repository.StaffUser;
import com.company.retail.auth.repository.StaffUserRepository;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.common.security.JwtTokenVerifier;
import com.company.retail.domain.exception.RFErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 双主体登录签发（切断点 ③：员工与客户两套 issuer/密钥/主体类型，互不可访问）。
 *
 * <p>员工侧：BCrypt 校验 + 角色/权限点进 token（与 floorplan 登录同构，防枚举口径一致）。</p>
 * <p>客户侧：客户自助登录依赖身份核验与同意会话（W3 customer-service 交付）；
 * W2 仅提供<b>显式开关的开发桥</b>（{@code retail.auth.customer-dev-login-enabled}，
 * 生产默认 false → 403），供双主体链路在 S2 前可被冒烟验证——不是客户登录的实现。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final StaffUserRepository userRepository;
    private final BCryptPasswordEncoder encoder;
    private final JwtTokenVerifier staffSigner;
    private final JwtTokenVerifier customerSigner;
    private final long tokenHours;
    private final boolean customerDevLoginEnabled;

    public AuthController(StaffUserRepository userRepository,
                          BCryptPasswordEncoder encoder,
                          JwtTokenVerifier staffTokenVerifier,
                          @Value("${retail.customer-auth.secret:dev-only-retail-customer-secret-change-me-in-prod-01234}")
                          String customerSecret,
                          @Value("${retail.auth.token-hours:8}") long tokenHours,
                          @Value("${retail.auth.customer-dev-login-enabled:false}") boolean customerDevLoginEnabled) {
        this.userRepository = userRepository;
        this.encoder = encoder;
        this.staffSigner = staffTokenVerifier;
        this.customerSigner = new JwtTokenVerifier(customerSecret, SubjectType.CUSTOMER);
        this.tokenHours = tokenHours;
        this.customerDevLoginEnabled = customerDevLoginEnabled;
    }

    /** 员工登录：POST /api/auth/staff/login {"username":"admin","password":"admin123"} */
    @PostMapping("/staff/login")
    public ApiResponse<Map<String, Object>> staffLogin(@RequestBody LoginRequest req) {
        // 用户不存在与密码错误同一出口（防枚举）
        StaffUser user = userRepository.findByUsername(req.username)
                .filter(u -> encoder.matches(req.password, u.passwordHash()))
                .filter(StaffUser::enabled)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_AUTH_001,
                        Map.of("reason", "BAD_CREDENTIALS")));

        List<String> roles = userRepository.findRoles(user.userId());
        java.util.Set<String> permissions = RolePermissions.effectivePermissions(roles);

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(tokenHours * 3600);
        String token = staffSigner.sign(user.username(), Map.of(
                "displayName", user.displayName(),
                "roles", roles,
                "perms", permissions.stream().sorted().toList()), tokenHours);

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("tokenType", "Bearer");
        data.put("expiresAt", expiresAt.toString());
        data.put("subjectType", SubjectType.EMPLOYEE.name());
        data.put("displayName", user.displayName());
        data.put("roles", roles);
        data.put("permissions", permissions.stream().sorted().toList());
        return ApiResponse.ok(data, RfSecurityConfig.traceId(
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()));
    }

    /**
     * 客户登录（W2 开发桥）：显式开关才可签发客户 token，供双主体隔离冒烟。
     * 客户真实认证（身份核验/OTP/同意会话）归 W3 customer-service。
     */
    @PostMapping("/customer/login")
    public ApiResponse<Map<String, Object>> customerLogin(@RequestBody LoginRequest req) {
        if (!customerDevLoginEnabled) {
            throw new BusinessException(RFErrorCode.RF_AUTH_002, Map.of(
                    "reason", "CUSTOMER_LOGIN_NOT_ENABLED",
                    "note", "客户自助登录由 customer-service 于 W3 交付；开发桥需显式开启配置"));
        }
        if (req.username == null || req.username.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", "username"));
        }
        String token = customerSigner.sign(req.username, Map.of("displayName", req.username), tokenHours);
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("tokenType", "Bearer");
        data.put("subjectType", SubjectType.CUSTOMER.name());
        data.put("expiresAt", Instant.now().plusSeconds(tokenHours * 3600).toString());
        return ApiResponse.ok(data, RfSecurityConfig.traceId(
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()));
    }

    /** 网关健康自证由本包 HealthController 提供（/api/auth/health）。 */

    public static class LoginRequest {
        public String username;
        public String password;
    }
}
