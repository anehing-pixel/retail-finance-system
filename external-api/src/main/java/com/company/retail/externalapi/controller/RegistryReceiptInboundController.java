package com.company.retail.externalapi.controller;

import com.company.platform.exception.BusinessException;
import com.company.platform.response.ApiResponse;
import com.company.retail.common.config.TraceIdFilter;
import com.company.retail.common.security.JwtTokenVerifier;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.externalapi.security.DmzGatekeeper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 登记机关回执入站端点（W-SIM，R07 I-10 / P-13 入站半边）。
 *
 * <p>链路：登记机关（模拟器/未来真实代办）签名回调 → DMZ 三关
 * （{@link DmzGatekeeper}：时间戳→nonce→签名→状态/scope/IP，全程 call_logs 留痕）→
 * 以系统服务身份转发 title-release {@code POST /api/release-orders/{id}/receipts}
 * （authority=EXTERNAL_REGISTRY，唯一权威回执，C-11）。</p>
 *
 * <p>请求体：{@code submissionRef / releaseOrderId / receiptRef / rawRef}；
 * 回执发起方必须持 scope {@code registry_receipts}（partner REG-SIM-01，
 * CONFIG 档案见 application.yml）。本端点只做协议承接与转发，不做业务裁决——
 * 回执有效性（状态机、代录≠完成）由 title-release 用例层裁决。</p>
 */
@RestController
public class RegistryReceiptInboundController {

    private static final String PARTNER_VERIFIED_BY = "REG-SIM-01";
    private static final Duration FORWARD_TIMEOUT = Duration.ofSeconds(10);

    private final DmzGatekeeper gatekeeper;
    private final JwtTokenVerifier staffVerifier;
    private final String titleReleaseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(FORWARD_TIMEOUT).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public RegistryReceiptInboundController(
            DmzGatekeeper gatekeeper,
            @Value("${retail.auth.secret}") String staffJwtSecret,
            @Value("${retail.clients.title-release.url}") String titleReleaseUrl) {
        this.gatekeeper = gatekeeper;
        this.staffVerifier = new JwtTokenVerifier(staffJwtSecret,
                com.company.platform.security.SubjectType.EMPLOYEE);
        this.titleReleaseUrl = titleReleaseUrl == null ? ""
                : (titleReleaseUrl.endsWith("/")
                ? titleReleaseUrl.substring(0, titleReleaseUrl.length() - 1) : titleReleaseUrl);
    }

    @PostMapping("/external/registry/receipts")
    public Object receipts(@RequestBody(required = false) String body, HttpServletRequest request) {
        String finalBody = body == null ? "" : body;
        return gatekeeper.guard(request, finalBody, () -> {
            try {
                return process(request, finalBody);
            } catch (java.io.IOException e) {
                throw new BusinessException(RFErrorCode.RF_SYS_001, Map.of("reason", "UPSTREAM_IO"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(RFErrorCode.RF_SYS_001, Map.of("reason", "FORWARD_INTERRUPTED"));
            }
        });
    }

    /** 三关通过后的协议承接 + 转发（受检异常统一在调用侧收敛为 RF_SYS_001）。 */
    private Object process(HttpServletRequest request, String finalBody) throws java.io.IOException,
            InterruptedException {
        JsonNode req = mapper.readTree(finalBody);
        long releaseOrderId = req.path("releaseOrderId").asLong(0);
        String receiptRef = req.path("receiptRef").asText("");
        String rawRef = req.path("rawRef").asText("");
        if (releaseOrderId <= 0 || receiptRef.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_PTNR_003,
                    Map.of("reason", "INVALID_PAYLOAD"));
        }
        if (titleReleaseUrl.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_SYS_001,
                    Map.of("reason", "TITLE_RELEASE_URL_NOT_CONFIGURED"));
        }
        // 系统服务身份：DMZ 转发不是"某个员工在点按钮"，签专用服务 token（独立主体名）
        String serviceToken = staffVerifier.sign("svc-external-dmz",
                Map.of("roles", List.of("SYSTEM"),
                        "perms", List.of("RELEASE_REGISTER_CONFIRM")),
                1);
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("authority", "EXTERNAL_REGISTRY");
        forward.put("receiptRef", receiptRef);
        forward.put("verifiedBy", PARTNER_VERIFIED_BY);
        forward.put("rawRef", rawRef.isBlank() ? "raw-" + receiptRef : rawRef);
        HttpRequest forwardReq = HttpRequest.newBuilder()
                .uri(URI.create(titleReleaseUrl + "/api/release-orders/" + releaseOrderId + "/receipts"))
                .timeout(FORWARD_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + serviceToken)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(forward)))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(forwardReq, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new BusinessException(RFErrorCode.RF_SYS_001,
                    Map.of("reason", "TITLE_RELEASE_UNREACHABLE"));
        }
        JsonNode upstream = mapper.readTree(resp.body());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("received", true);
        data.put("partnerCode", request.getHeader("X-Partner-Code"));
        data.put("submissionRef", req.path("submissionRef").asText(""));
        data.put("forwardStatus", resp.statusCode());
        data.put("upstream", upstream.path("data"));
        Object traceId = request.getAttribute(TraceIdFilter.ATTR);
        return ApiResponse.ok(data, traceId == null ? UUID.randomUUID().toString()
                : String.valueOf(traceId));
    }
}
