package com.company.retail.common.contract;

import com.company.retail.core.integration.DisbursementGateChecker;

/**
 * frozen {@link FeignContracts} 的消费访问桥（W5，retail-core 自有文件）。
 *
 * <p><b>为什么存在</b>：冻结契约的嵌套门禁接口（{@code ContractGateClient} 等）、
 * {@code ApiResponseShape} 与 {@code GateResult} 都是<b>包私有</b>，外部包无法命名这些类型
 * （编译期即拒）。本类放在同一包内做纯类型访问桥，把 frozen FeignClient bean 适配成
 * 消费方自己的 {@code DisbursementGateChecker.GateClient} 端口——<b>不改冻结契约一行</b>。</p>
 *
 * <p><b>不新增契约语义</b>：路径、形状、判定语义全部以冻结源为准；若共享区后续把
 * FeignContracts 成员放开为 public，本桥即废（记入 W5 报告的共享区改进建议）。</p>
 */
public final class GateClients {

    private GateClients() {
    }

    /** 合同门禁适配（frozen ContractGateClient → 消费方端口）。 */
    public static DisbursementGateChecker.GateClient contractGate(
            org.springframework.context.ApplicationContext context,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return id -> {
            FeignContracts.ApiResponseShape<?> shape =
                    context.getBean(FeignContracts.ContractGateClient.class).check(id);
            return toAnswer(shape, mapper);
        };
    }

    /** 审查门禁适配（frozen UnderwritingGateClient → 消费方端口）。 */
    public static DisbursementGateChecker.GateClient underwritingGate(
            org.springframework.context.ApplicationContext context,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return id -> {
            FeignContracts.ApiResponseShape<?> shape =
                    context.getBean(FeignContracts.UnderwritingGateClient.class).check(id);
            return toAnswer(shape, mapper);
        };
    }

    private static DisbursementGateChecker.GateAnswer toAnswer(
            FeignContracts.ApiResponseShape<?> shape, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        // GateResult（包私有 record）→ Map（键：allowed / reasonCode），消费方不触碰该类型
        java.util.Map<String, Object> data = shape.data == null
                ? java.util.Map.of()
                : mapper.convertValue(shape.data, java.util.Map.class);
        return new DisbursementGateChecker.GateAnswer(shape.code, data);
    }
}
