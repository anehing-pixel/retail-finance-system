package com.company.retail.common.security;

import com.company.platform.security.SubjectType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双主体 JWT 机制单测（W2 门禁项：签发↔验签、主体隔离、透传）。
 */
class JwtTokenVerifierTest {

    private static final String STAFF_SECRET = "staff-secret-0123456789abcdef0123456789abcdef";
    private static final String CUSTOMER_SECRET = "customer-secret-0123456789abcdef012345";

    @Test
    void staffTokenRoundTripKeepsRolesAndPermissions() {
        JwtTokenVerifier staffSigner = new JwtTokenVerifier(STAFF_SECRET, SubjectType.EMPLOYEE);
        JwtTokenVerifier staffVerifier = new JwtTokenVerifier(STAFF_SECRET, SubjectType.EMPLOYEE);
        String token = staffSigner.sign("emp-001",
                Map.of("roles", java.util.List.of("UW_MANAGER"),
                        "perms", java.util.List.of("UNDERWRITING_DECIDE")), 8);

        var verified = staffVerifier.verify(token);
        assertEquals("emp-001", verified.subject());
        assertEquals(SubjectType.EMPLOYEE, verified.subjectType());
        assertEquals(Set.of("UW_MANAGER"), verified.roles());
        assertEquals(Set.of("UNDERWRITING_DECIDE"), verified.permissions());
    }

    @Test
    void staffSecretCannotVerifyCustomerSignedToken() {
        // 两把密钥互不相同（R00 §6）：客户密钥签的 token 在员工侧验签必须 401
        JwtTokenVerifier customerSigner = new JwtTokenVerifier(CUSTOMER_SECRET, SubjectType.CUSTOMER);
        JwtTokenVerifier staffVerifier = new JwtTokenVerifier(STAFF_SECRET, SubjectType.EMPLOYEE);
        String customerToken = customerSigner.sign("cust-100", Map.of(), 1);

        var ex = assertThrows(com.company.platform.exception.BusinessException.class,
                () -> staffVerifier.verify(customerToken));
        assertEquals("RF_AUTH_001", ex.getErrorCode().getCode());
    }

    @Test
    void subjectTypeClaimMismatchIsRejectedAtVerification() {
        // 即使密钥相同，主体类型声明不符也按未认证拒绝（切断点 ③ 在验签层强制）
        JwtTokenVerifier signer = new JwtTokenVerifier(STAFF_SECRET, SubjectType.EMPLOYEE);
        JwtTokenVerifier staffVerifier = new JwtTokenVerifier(STAFF_SECRET, SubjectType.EMPLOYEE);
        String forged = signer.sign("emp-001", Map.of("subjectType", "CUSTOMER"), 1);

        var ex = assertThrows(com.company.platform.exception.BusinessException.class,
                () -> staffVerifier.verify(forged));
        assertEquals("RF_AUTH_001", ex.getErrorCode().getCode());
        assertEquals("SUBJECT_TYPE_MISMATCH", ex.getDetails().get("reason"));
    }

    @Test
    void garbageTokenIsUnauthorized() {
        JwtTokenVerifier staffVerifier = new JwtTokenVerifier(STAFF_SECRET, SubjectType.EMPLOYEE);
        var ex = assertThrows(com.company.platform.exception.BusinessException.class,
                () -> staffVerifier.verify("not-a-jwt"));
        assertEquals(401, ex.getErrorCode().getHttpStatus());
    }

    @Test
    void weakSecretIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new JwtTokenVerifier("short", SubjectType.EMPLOYEE));
    }

    @Test
    void customerVerifierRejectsEmployeeSubjectType() {
        JwtTokenVerifier staffSigner = new JwtTokenVerifier(STAFF_SECRET, SubjectType.EMPLOYEE);
        JwtTokenVerifier customerVerifier = new JwtTokenVerifier(CUSTOMER_SECRET, SubjectType.CUSTOMER);
        String staffToken = staffSigner.sign("emp-001", Map.of(), 8);
        assertThrows(com.company.platform.exception.BusinessException.class,
                () -> customerVerifier.verify(staffToken));
    }

    // ---- Feign 透传 ----

    @Test
    void passthroughCopiesAuthorizationAndRequestId() {
        JwtPassthroughInterceptor interceptor = new JwtPassthroughInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer abc.def.ghi");
        request.addHeader(JwtPassthroughInterceptor.HEADER_REQUEST_ID, "req-42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            feign.RequestTemplate template = new feign.RequestTemplate();
            interceptor.apply(template);
            assertEquals("Bearer abc.def.ghi",
                    template.headers().get("Authorization").iterator().next());
            assertEquals("req-42",
                    template.headers().get("X-Request-Id").iterator().next());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void passthroughAddsNothingOutsideRequestContext() {
        // outbox 消费线程没有 HTTP 上下文（消费铁律 3）：透传器不得凭空造头
        RequestContextHolder.resetRequestAttributes();
        JwtPassthroughInterceptor interceptor = new JwtPassthroughInterceptor();
        feign.RequestTemplate template = new feign.RequestTemplate();
        interceptor.apply(template);
        assertNull(template.headers().get("Authorization"));
        assertFalse(template.headers().containsKey("X-Request-Id"));
        assertTrue(template.headers().isEmpty() || template.headers().get("Content-Length") == null);
    }
}
