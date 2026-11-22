package com.company.retail.core.integration;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * G8 代付前门禁（fail-closed，R09 §7.1）——frozen {@code FeignContracts} 的<b>消费方逻辑</b>。
 *
 * <p>语义（R02 §7.1 / ADR-RF-001）：任一门禁「查不到 / 超时 / 异常 / 非 2xx / 非 OK 码 /
 * allowed != true」一律拒绝代付，宁可误拒不可放过（UNKNOWN 不视为通过，严禁 fallback 放行）。</p>
 *
 * <p>实际 Feign 客户端由 {@code GateClientConfig} 经 frozen 契约访问桥装配；
 * 单测用挡板 {@link GateClient} 直接覆盖 fail-closed 语义，不起真依赖进程。</p>
 */
public class DisbursementGateChecker {

    private static final Logger log = LoggerFactory.getLogger(DisbursementGateChecker.class);

    private final GateClient contractGate;
    private final GateClient underwritingGate;

    public DisbursementGateChecker(GateClient contractGate, GateClient underwritingGate) {
        this.contractGate = contractGate;
        this.underwritingGate = underwritingGate;
    }

    /**
     * 合同门禁 + 审查门禁全过才放行；否则抛 {@code RF_LOAN_001}（details 带门禁 reasonCode）。
     *
     * @param contractId         合同 ID（合同 EFFECTIVE 且法定书面文件已交付）
     * @param underwritingCaseId 审查案件 ID（审查已批准且未撤回）
     */
    public void checkAll(long contractId, long underwritingCaseId) {
        checkGate("contract", contractId, contractGate, "CONTRACT");
        checkGate("underwriting", underwritingCaseId, underwritingGate, "UNDERWRITING");
    }

    private void checkGate(String gateName, long id, GateClient client, String reasonPrefix) {
        GateAnswer answer;
        try {
            answer = client.check(id);
        } catch (Exception e) {
            // 超时 / 连接失败 / 非 2xx（Feign ErrorDecoder 抛异常）：fail-closed
            log.warn("代付门禁不可达，拒绝代付 gate={} id={} cause={}", gateName, id, e.getMessage());
            throw reject(reasonPrefix + "_GATE_UNREACHABLE");
        }
        if (answer == null || !"OK".equals(answer.code())) {
            // 非 OK 业务码：fail-closed（ApiResponse 禁裸对象的原因所在）
            log.warn("代付门禁返回非 OK，拒绝代付 gate={} id={}", gateName, id);
            throw reject(reasonPrefix + "_GATE_NOT_OK");
        }
        if (!Boolean.TRUE.equals(answer.data().get("allowed"))) {
            Object reason = answer.data().get("reasonCode");
            log.warn("代付门禁拒绝，gate={} id={} reasonCode={}", gateName, id, reason);
            throw reject(String.valueOf(reason == null ? reasonPrefix + "_GATE_DENIED" : reason));
        }
    }

    private static BusinessException reject(String reasonCode) {
        return new BusinessException(RFErrorCode.RF_LOAN_001, "代付门禁未全过，拒绝代付（G8 fail-closed）",
                Map.of("reasonCode", reasonCode));
    }

    /** 门禁客户端端口（生产：frozen FeignClient 适配；测试：挡板）。 */
    @FunctionalInterface
    public interface GateClient {
        GateAnswer check(long id);
    }

    /** 门禁响应（形状对齐 frozen ApiResponseShape + GateResult：code + allowed/reasonCode）。 */
    public record GateAnswer(String code, Map<String, Object> data) {
    }
}
