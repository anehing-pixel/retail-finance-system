package com.company.retail.common.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 同意门禁 fail-closed 消费 Facade（W4）。
 *
 * <p><b>为什么在本包</b>：冻结契约 {@code FeignContracts} 的嵌套接口
 * {@code ConsentGateClient} 与 {@code ApiResponseShape} 是 package-private
 * （W2 冻结代码，W4 不可改），跨包无法命名/访问。本类放同包内桥接——
 * 只<b>消费</b>冻结契约，不新增契约；已登记「需要对共享区改动的事项」：
 * 建议把 {@code GateResult} / {@code ConsentGateClient} / {@code ApiResponseShape}
 * 提为 public 且 {@code ApiResponseShape} 改 static，本 Facade 即可下沉 common。</p>
 *
 * <p><b>fail-closed 语义</b>（R02 §7.1）：{@code true} 当且仅当
 * {@code HTTP 2xx && code==OK && data.allowed==true}；
 * 查不到 / 超时 / 异常 / UNKNOWN / 非 2xx 一律 {@code false}（拒绝推进）。
 * 禁止缓存结果（同意可秒级撤回，INV-5）。超时 2s。</p>
 *
 * <p><b>原始 HTTP 兜底（W2 契约缺陷的临时绕行）</b>：{@code ApiResponseShape} 是
 * 非静态内部类，Jackson 无法反序列化（实测 InvalidDefinitionException），真实 Feign
 * 链路必然解码异常。Feign 调用异常时按<b>同一 URL 属性、同一 JSON 契约</b>走
 * java.net.http 原始调用；兜底同样 fail-closed——任何异常 / 非 2xx / code!=OK /
 * allowed!=true 一律 {@code false}。W2 修复契约后兜底自动失效（Feign 路径恢复）。</p>
 */
public class ConsentGateFacade {

    private static final String GATE_PATH = "/api/consents/%d/disbursement-gate";

    private final FeignContracts.ConsentGateClient client;
    private final String customerBaseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConsentGateFacade(FeignContracts.ConsentGateClient client, String customerBaseUrl) {
        this.client = client;
        this.customerBaseUrl = customerBaseUrl;
    }

    /** 同意有效且未撤回才返回 true；任何异常/UNKNOWN 一律 false（fail-closed）。 */
    public boolean isConsentValid(long consentId) {
        if (consentId <= 0) {
            return false;
        }
        try {
            FeignContracts.ApiResponseShape<?> response = client.check(consentId);
            if (response == null || !"OK".equals(response.code)) {
                // Feign 路径语义失败：非 2xx 已转 FeignException，能到这里说明 code 非 OK
                if (response == null) {
                    return false;
                }
                return false;
            }
            // GateResult 是 package-private record：Object 宽化 + Jackson 读值（JSON 形状契约不变）
            Map<?, ?> data = objectMapper.convertValue(response.data, Map.class);
            return Boolean.TRUE.equals(data.get("allowed"));
        } catch (Exception feignError) {
            // W2 缺陷绕行：ApiResponseShape 非静态内部类无法被 Jackson 解码
            return checkViaRawHttp(consentId);
        }
    }

    /** 原始 HTTP 兜底：同 URL 属性 + 同 JSON 契约；任何异常一律 false（fail-closed）。 */
    private boolean checkViaRawHttp(long consentId) {
        if (customerBaseUrl == null || customerBaseUrl.isBlank()) {
            // 双模 URI 空串回退 lb://：原始调用不支持 lb，直接 fail-closed
            return false;
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
