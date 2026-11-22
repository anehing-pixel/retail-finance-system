package com.company.retail.externalapi.controller;

import com.company.platform.response.ApiResponse;
import com.company.retail.common.config.TraceIdFilter;
import com.company.retail.externalapi.security.DmzGatekeeper;
import com.company.retail.externalapi.security.PartnerSignatureVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * DMZ 端点（R09 §3 外部端点骨架）：
 * <ul>
 *   <li>{@code GET /external/health}：免签健康自证；</li>
 *   <li>{@code POST /external/echo}：签名回显自证——W9-B 起走
 *       {@link DmzGatekeeper} 三关（时间戳→nonce→签名→状态/scope/IP，R07 §5.2），
 *       全量落 {@code integration.call_logs}。</li>
 * </ul>
 * 业务端点（/external/dms/applications 等）随 S3 逐通道落，不在 W2 预置占位。
 */
@RestController
public class ExternalApiController {

    private final DmzGatekeeper gatekeeper;

    public ExternalApiController(DmzGatekeeper gatekeeper) {
        this.gatekeeper = gatekeeper;
    }

    /** 健康自证（DMZ 独立进程存活即 UP；免签，不计入 partner 调用留痕）。 */
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
     * 校验顺序按 §5.2，全部通过才回显；任何一关失败留痕后仍以对应 RF_PTNR_* 错误返回。
     */
    @PostMapping("/external/echo")
    public Object echo(@RequestBody(required = false) String body, HttpServletRequest request) {
        String finalBody = body == null ? "" : body;
        return gatekeeper.guard(request, finalBody, () -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("partnerCode", request.getHeader("X-Partner-Code"));
            data.put("nonce", request.getHeader("X-Nonce"));
            data.put("bodyBytes", finalBody.getBytes(StandardCharsets.UTF_8).length);
            Object traceId = request.getAttribute(TraceIdFilter.ATTR);
            return ApiResponse.ok(data, traceId == null ? UUID.randomUUID().toString()
                    : String.valueOf(traceId));
        });
    }

    /** 供冒烟复算规范化串（只读机制辅助，不含密钥、免签）。 */
    @PostMapping("/external/echo/canonical")
    public Object canonical(@RequestBody(required = false) String body, HttpServletRequest request) {
        String finalBody = body == null ? "" : body;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("canonical", PartnerSignatureVerifier.canonical(request.getMethod(),
                request.getRequestURI(),
                request.getHeader("X-Timestamp"),
                request.getHeader("X-Nonce"),
                request.getHeader("X-Key-Version") == null ? "v1" : request.getHeader("X-Key-Version"),
                finalBody));
        return ApiResponse.ok(data, UUID.randomUUID().toString());
    }
}
