package com.company.retail.common.contract;

import com.company.retail.common.outbound.OutboundHttpClient;
import com.company.retail.common.outbound.OutboundHttpClientFactory;
import com.company.retail.common.outbound.OutboundProfile;
import com.company.retail.common.outbound.OutboundResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.company.retail.servicing.application.ConsentValidityGate;

import java.util.Map;
import java.util.UUID;

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
    /** P0-7：raw 兜底统一走过站客户端（GATE_RAW：连接超时 + 熔断，不重试）。 */
    private final OutboundHttpClient rawClient;

    public ServicingConsentGate(FeignContracts.ConsentGateClient client, String customerBaseUrl) {
        this(client, customerBaseUrl, OutboundHttpClientFactory.defaults());
    }

    public ServicingConsentGate(FeignContracts.ConsentGateClient client, String customerBaseUrl,
                                OutboundHttpClientFactory outbound) {
        this.client = client;
        this.customerBaseUrl = customerBaseUrl;
        this.rawClient = outbound.client("consent-gate-raw", OutboundProfile.GATE_RAW);
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
            OutboundResult response = rawClient.get(
                    customerBaseUrl + String.format(GATE_PATH, consentId),
                    Map.of("Authorization", currentAuthorization(),
                            "X-Request-Id", UUID.randomUUID().toString()));
            if (!response.is2xx()) {
                return false;
            }
            JsonNode body = objectMapper.readTree(response.getBody());
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
