package com.company.retail.externalapi.controller;

import com.company.platform.response.ApiResponse;
import com.company.retail.common.config.TraceIdFilter;
import com.company.retail.externalapi.security.PartnerRegistry;
import com.company.retail.externalapi.security.PartnerSignatureVerifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * DMZ 端点（R09 §3 外部端点骨架）：
 * <ul>
 *   <li>{@code GET /external/health}：免签健康自证；</li>
 *   <li>{@code POST /external/echo}：签名回显自证——冒烟用它证明
 *       「规范化串构造 → HMAC → 常量时间比较 → 错误码语义」整条验签链路真实生效。</li>
 * </ul>
 * 业务端点（/external/dms/applications 等）随 S3 逐通道落，不在 W2 预置占位。
 */
@RestController
public class ExternalApiController {

    private final PartnerRegistry registry;

    public ExternalApiController(PartnerRegistry registry) {
        this.registry = registry;
    }

    /** 健康自证（DMZ 独立进程存活即 UP）。 */
    @GetMapping("/external/health")
    public Object health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "external-api");
        data.put("status", "UP");
        return ApiResponse.ok(data, UUID.randomUUID().toString());
    }

    /**
     * 签名回显：请求头 {@code X-Partner-Code / X-Nonce / X-Timestamp / X-Key-Version / X-Signature}，
     * 规范化串 = METHOD+PATH+timestamp+nonce+key_version+SHA256(body)（R07 §5.1）。
     * 校验顺序按 §5.2：partner 存在 → nonce → 时间戳 → 签名（常量时间比较）。
     */
    @PostMapping("/external/echo")
    public Object echo(@RequestHeader("X-Partner-Code") String partnerCode,
                       @RequestHeader("X-Nonce") String nonce,
                       @RequestHeader("X-Timestamp") String timestamp,
                       @RequestHeader(value = "X-Key-Version", defaultValue = "v1") String keyVersion,
                       @RequestHeader("X-Signature") String signature,
                       @RequestBody(required = false) String body,
                       jakarta.servlet.http.HttpServletRequest request) {
        registry.verify("POST", request.getRequestURI(), partnerCode, timestamp,
                nonce, keyVersion, body == null ? "" : body, signature);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("partnerCode", partnerCode);
        data.put("nonce", nonce);
        data.put("bodyBytes", body == null ? 0 : body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        Object traceId = request.getAttribute(TraceIdFilter.ATTR);
        return ApiResponse.ok(data, traceId == null ? UUID.randomUUID().toString() : String.valueOf(traceId));
    }

    /** 供冒烟复算规范化串（只读机制辅助，不含密钥）。 */
    @PostMapping("/external/echo/canonical")
    public Object canonical(@RequestHeader("X-Timestamp") String timestamp,
                            @RequestHeader("X-Nonce") String nonce,
                            @RequestHeader(value = "X-Key-Version", defaultValue = "v1") String keyVersion,
                            @RequestBody(required = false) String body,
                            jakarta.servlet.http.HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("canonical", PartnerSignatureVerifier.canonical("POST", request.getRequestURI(),
                timestamp, nonce, keyVersion, body == null ? "" : body));
        return ApiResponse.ok(data, UUID.randomUUID().toString());
    }
}
