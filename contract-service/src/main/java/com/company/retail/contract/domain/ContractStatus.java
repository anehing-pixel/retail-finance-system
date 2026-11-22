package com.company.retail.contract.domain;

import java.util.Map;
import java.util.Set;

/**
 * 合同状态机（R04 §3.3 穷举，禁止直接 {@code UPDATE ... SET status}，状态变更只走 {@link Contract} 聚合方法）。
 *
 * <pre>
 * DRAFT → DOCUMENTS_DELIVERED → SIGNED → COOLING_OFF / EFFECTIVE
 * COOLING_OFF → EFFECTIVE / CANCELLED
 * 终态：CANCELLED / TERMINATED（互斥，I-7）
 * </pre>
 */
public enum ContractStatus {

    DRAFT,
    DOCUMENTS_DELIVERED,
    SIGNED,
    COOLING_OFF,
    EFFECTIVE,
    /** 终态。冷静期内行使解约（恢复原状清算由后续波次承接）。 */
    CANCELLED,
    /** 终态。结清/解约后。 */
    TERMINATED;

    /** 迁移表（R04 §3.3；终态集合显式为空，I-7 / AGENTS B-7）。 */
    private static final Map<ContractStatus, Set<ContractStatus>> ALLOWED = Map.of(
            DRAFT, Set.of(DOCUMENTS_DELIVERED),
            DOCUMENTS_DELIVERED, Set.of(SIGNED, CANCELLED),
            SIGNED, Set.of(COOLING_OFF, EFFECTIVE),
            COOLING_OFF, Set.of(EFFECTIVE, CANCELLED),
            EFFECTIVE, Set.of(TERMINATED),
            CANCELLED, Set.of(),
            TERMINATED, Set.of());

    /** 终态显式且互斥（I-7）。 */
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    public boolean canTransitionTo(ContractStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
