package com.company.retail.common.contract;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Feign 门禁契约（冻结，R09 §7 / R02 §7.1）。
 *
 * <p><b>唯一允许的同步调用是代付前门禁（fail-closed）</b>：retail-core 代付前依次问
 * 合同 / 同意 / 审查三方，任一 UNKNOWN / 超时 / fallback / 非 2xx → 拒付并整体回滚。
 * 事后状态同步一律走 outbox（{@link OutboxPublisher}），不在此链路。</p>
 *
 * <p>冻结规则（R09 §7.2）：</p>
 * <ul>
 *   <li>返回一律 {@code ApiResponse<GateResult>}，禁止裸对象；</li>
 *   <li>{@code url = "${retail.clients.<key>.url:}"} 双模——空串回退 {@code lb://服务名}，
 *       本地无注册中心时环境变量覆盖直连；属性键小写单词；</li>
 *   <li>调用方启动类 {@code @EnableFeignClients(basePackages = "com.company.retail.common.contract")}；</li>
 *   <li>并行包（W3/W4/W5）只消费、不得新增未登记契约（R11 §4.1 硬耦合 4）。</li>
 * </ul>
 */
public final class FeignContracts {

    private FeignContracts() {
    }

    /**
     * 门禁判定结果：{@code allowed=false} 与 {@code reasonCode} 语义由提供方定义，
     * 调用方只认 {@code allowed==true} 且 HTTP 2xx；其余一律 fail-closed。
     */
    record GateResult(boolean allowed, String reasonCode) {
    }

    /** BC-5 合同门禁：合同已生效且法定书面文件已交付（RF_CTR_001 的判定前置）。 */
    @FeignClient(name = "contract-service", contextId = "contractGate",
            url = "${retail.clients.contract.url:}")
    interface ContractGateClient {

        @GetMapping("/api/contracts/{contractId}/disbursement-gate")
        ApiResponseShape<GateResult> check(@PathVariable("contractId") long contractId);
    }

    /** BC-2 同意门禁：同意有效且未撤回（同意可秒级撤回，禁止缓存门禁结果）。 */
    @FeignClient(name = "customer-service", contextId = "consentGate",
            url = "${retail.clients.customer.url:}")
    interface ConsentGateClient {

        @GetMapping("/api/consents/{consentId}/disbursement-gate")
        ApiResponseShape<GateResult> check(@PathVariable("consentId") long consentId);
    }

    /** BC-3 审查门禁：审查已批准且未撤回。 */
    @FeignClient(name = "underwriting-service", contextId = "underwritingGate",
            url = "${retail.clients.underwriting.url:}")
    interface UnderwritingGateClient {

        @GetMapping("/api/underwriting/cases/{caseId}/disbursement-gate")
        ApiResponseShape<GateResult> check(@PathVariable("caseId") long caseId);
    }

    /** 门禁返回形状：与 {@code com.company.platform.response.ApiResponse} 同构（避免契约层再引依赖）。 */
    class ApiResponseShape<T> {
        public String code;
        public String message;
        public T data;
        public String traceId;
    }
}
