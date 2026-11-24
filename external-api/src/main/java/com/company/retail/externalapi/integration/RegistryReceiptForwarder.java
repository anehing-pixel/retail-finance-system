package com.company.retail.externalapi.integration;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.outbound.OutboundHttpClient;
import com.company.retail.common.outbound.OutboundResult;
import com.company.retail.domain.exception.RFErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * DMZ 回执转发器（P0-7）：锁内调用 title-release，<b>绝不重试</b>。
 *
 * <p>上游非 2xx 保留原响应语义（status + body 回给调用方），只有传输失败
 * （超时/熔断/连接失败）才收敛为 {@code RF_SYS_001 + TITLE_RELEASE_UNREACHABLE}；
 * 与改造前行为一致。</p>
 */
public final class RegistryReceiptForwarder {

    public record Outcome(int status, JsonNode body) {
    }

    private final OutboundHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public RegistryReceiptForwarder(OutboundHttpClient client) {
        this.client = client;
    }

    public Outcome forward(String baseUrl, long releaseOrderId, String authorization,
                           Map<String, Object> payload, String idempotencyKey) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BusinessException(RFErrorCode.RF_SYS_001,
                    Map.of("reason", "FORWARD_PAYLOAD_SERIALIZE"));
        }
        OutboundResult response = client.postJson(
                baseUrl + "/api/release-orders/" + releaseOrderId + "/receipts",
                json,
                Map.of("Authorization", authorization,
                        "Idempotency-Key", idempotencyKey));
        if (response.getStatus() < 0) {
            throw new BusinessException(RFErrorCode.RF_SYS_001,
                    Map.of("reason", "TITLE_RELEASE_UNREACHABLE"));
        }
        try {
            return new Outcome(response.getStatus(), mapper.readTree(response.getBody()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BusinessException(RFErrorCode.RF_SYS_001,
                    Map.of("reason", "FORWARD_RESPONSE_PARSE"));
        }
    }
}
