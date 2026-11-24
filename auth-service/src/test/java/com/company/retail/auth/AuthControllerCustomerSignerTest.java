package com.company.retail.auth;

import com.company.platform.exception.BusinessException;
import com.company.platform.response.ApiResponse;
import com.company.platform.security.SubjectType;
import com.company.retail.auth.repository.StaffUser;
import com.company.retail.auth.repository.StaffUserRepository;
import com.company.retail.common.security.JwtTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 客户登录签发必须复用 RfSecurityConfig 的客户侧验签器（R11 P0-8：密钥单一来源，
 * 不允许 Controller 自己再读一份带默认值的密钥）。
 */
class AuthControllerCustomerSignerTest {

    private static final String STAFF_SECRET = "staff-secret-0123456789abcdef0123456789abcdef";
    private static final String CUSTOMER_SECRET = "customer-secret-0123456789abcdef01234567";

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void customerLoginSignsWithSharedCustomerVerifier() {
        JwtTokenVerifier staffVerifier = new JwtTokenVerifier(STAFF_SECRET, SubjectType.EMPLOYEE);
        JwtTokenVerifier customerVerifier = new JwtTokenVerifier(CUSTOMER_SECRET, SubjectType.CUSTOMER);
        StaffUserRepository repository = new StaffUserRepository(null) {
            @Override
            public Optional<StaffUser> findByUsername(String username) {
                return Optional.empty();
            }
        };
        AuthController controller = new AuthController(repository, new BCryptPasswordEncoder(),
                staffVerifier, customerVerifier, 8, true);

        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        AuthController.LoginRequest request = new AuthController.LoginRequest();
        request.username = "cust-001";

        ApiResponse<Map<String, Object>> response = controller.customerLogin(request);
        String token = String.valueOf(response.getData().get("token"));

        assertEquals("cust-001", customerVerifier.verify(token).subject());
        assertThrows(BusinessException.class, () -> staffVerifier.verify(token));
    }
}
