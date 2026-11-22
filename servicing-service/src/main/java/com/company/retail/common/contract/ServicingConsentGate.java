package com.company.retail.common.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.company.retail.servicing.application.ConsentValidityGate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * servicing 侧同意有效性门禁（INV-5 / RB-1，W7）。
 *
 * <p><b>fail-closed 语义</b>（R02 §7.1，与 W4 ConsentGateFacade 同构）：
 * {@code true} 当且仅当 {@code HTTP 2xx && code==OK && data.allowed==true}；
 * 查不到 / 超时 / 异常 / 非 2xx 一律 {@code false}（拒绝登记触发）。
 * 禁止缓存结果（同意可秒级撤回，INV-5）。超时 2s。</p>
 *
 * <p>原始 HTTP 兜底与 W4 相同口径：frozen 契约的 {@code ApiResponseShape} 是非静态内部类，
 * Feign 解码必然异常时按同一 URL 属性、同一 JSON 契约走 java.net.http；
 * 兜底同样 fail-closed。</p>
 */
public class ServicingConsentGate implements ConsentValidityGate {

    private static final String GATE_PATH = "/api/consents/%d/disbursement-gate";

    private final FeignContracts.ConsentGateClient client;
    private final String customerBaseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ServicingConsentGate(FeignContracts.ConsentGateClient client, String customerBaseUrl) {
        this.client = client;
        this.customerBaseUrl = customerBaseUrl;
    }

    @Override
    public boolean isValid(long consentId) {
        if (consentId <= 0) {
            return false;
        }
        try {
            FeignContracts.ApiResponseShape<?> response = client.check(consentId);
            if (response == null || !"OK".equals(response.code)) {
                return false;
            }
            Map<?, ?> data = objectMapper.convertValue(response.data, Map.class);
            return Boolean.TRUE.equals(data.get("allowed"));
        } catch (Exception feignError) {
            return checkViaRawHttp(consentId);
        }
    }

    /** 原始 HTTP 兜底：同 URL 属性 + 同 JSON 契约；任何异常一律 false（fail-closed）。 */
    private boolean checkViaRawHttp(long consentId) {
        if (customerBaseUrl == null || customerBaseUrl.isBlank()) {
            return false;   // 双模 URI 空串回退 lb://：原始调用不支持 lb，直接 fail-closed
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(customerBaseUrl + String.format(GATE_PATH, consentId)))
                    .timeout(Duration.ofSeconds(2))
                    .header("Authorization", currentAuthorization())
                    .header("X-Request-Id", java.util.UUID.randomUUID().toString())
                    .GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return false;
            }
            JsonNode body = objectMapper.readTree(response.body());
            if (!"OK".equals(body.path("code").asText())) {
                return false;
            }
            return body.path("data").path("allowed").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 门禁零信任：透传当前请求的 Authorization（无请求上下文时不透传，由下游拒绝）。 */
    private static String currentAuthorization() {
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attrs) {
            String authorization = attrs.getRequest().getHeader("Authorization");
            return authorization == null ? "" : authorization;
        }
        return "";
    }
}
