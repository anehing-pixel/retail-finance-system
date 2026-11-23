package com.company.retail.externalapi.controller;

import com.company.platform.exception.BusinessException;
import com.company.platform.response.ApiResponse;
import com.company.retail.common.config.TraceIdFilter;
import com.company.retail.common.lock.DistributedLockManager;
import com.company.retail.common.lock.LockKeys;
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
    private final DistributedLockManager locks;
    private final java.time.Duration lockWait;
    private final String titleReleaseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(FORWARD_TIMEOUT).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public RegistryReceiptInboundController(
            DmzGatekeeper gatekeeper,
            @Value("${retail.auth.secret}") String staffJwtSecret,
            @Value("${retail.clients.title-release.url}") String titleReleaseUrl,
            DistributedLockManager locks,
            @Value("${retail.lock.wait-time-ms:3000}") long lockWaitMs) {
        this.gatekeeper = gatekeeper;
        this.staffVerifier = new JwtTokenVerifier(staffJwtSecret,
                com.company.platform.security.SubjectType.EMPLOYEE);
        this.locks = locks;
        this.lockWait = java.time.Duration.ofMillis(lockWaitMs);
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
        // 回调幂等（ADR-RF-013 / ADR-RF-015）：外部方「换 nonce 重推」同一业务回执时，DMZ 的 nonce 关
        // （只挡同一 nonce 重放）挡不住 —— 按业务键（订单 + 回执号）加分布式锁串行化转发。
        // 锁在临界区结束即释放（DMZ 无本地事务）；下游 title-release 另有回执登记锁 + 唯一索引兜底。
        return locks.callWithLock(LockKeys.dmzCallback(releaseOrderId, receiptRef), lockWait,
                () -> forward(request, req, releaseOrderId, receiptRef, rawRef));
    }

    /** JSON 序列化（受检异常收敛为 RF_SYS_001）。 */
    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BusinessException(RFErrorCode.RF_SYS_001,
                    Map.of("reason", "FORWARD_PAYLOAD_SERIALIZE"));
        }
    }

    /** JSON 解析（受检异常收敛为 RF_SYS_001）。 */
    private JsonNode parseJson(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BusinessException(RFErrorCode.RF_SYS_001,
                    Map.of("reason", "FORWARD_RESPONSE_PARSE"));
        }
    }

    /** 锁内转发：签系统服务 token → 转发 title-release → 组装响应。 */
    private Object forward(HttpServletRequest request, JsonNode req, long releaseOrderId,
                           String receiptRef, String rawRef) {
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
                .POST(HttpRequest.BodyPublishers.ofString(toJson(forward)))
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
        JsonNode upstream = parseJson(resp.body());
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
