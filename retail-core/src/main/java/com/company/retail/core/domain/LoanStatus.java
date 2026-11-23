package com.company.retail.core.domain;

import java.util.Map;
import java.util.Set;

/**
 * 债权状态机（R04 §3.4 完整口径，W6 收口）。
 *
 * <p>与 R04 的对应：R04 的 {@code CREATED} 在 V6 迁移落定为
 * {@code PENDING_DISBURSEMENT}（建表默认值口径，W2 冻结）——两者是同一状态的不同名，
 * 本枚举以 V6 字面为准，其余状态与 R04 一致。</p>
 *
 * <p>终态四分（B-7）：SETTLED / EARLY_SETTLED / WRITTEN_OFF / CANCELLED 互斥、不可再迁移。
 * {@code SETTLED} 与 {@code EARLY_SETTLED} 分开的原因（R04 §3.4）：
 * 结清金额计算（C-10）、征信报送终态码、解押触发时点都不同。</p>
 */
public enum LoanStatus {

    PENDING_DISBURSEMENT,
    DISBURSING,
    ACTIVE,
    DISBURSE_FAILED,
    DELINQUENT,
    SETTLED,
    EARLY_SETTLED,
    WRITTEN_OFF,
    CANCELLED;

    /** 迁移表（R04 §3.4 全量；终态集合显式为空，I-7：终态不可再迁移）。 */
    private static final Map<LoanStatus, Set<LoanStatus>> ALLOWED = Map.of(
            PENDING_DISBURSEMENT, Set.of(DISBURSING, CANCELLED),
            DISBURSING, Set.of(ACTIVE, DISBURSE_FAILED),
            ACTIVE, Set.of(DELINQUENT, SETTLED, EARLY_SETTLED, WRITTEN_OFF),
            DISBURSE_FAILED, Set.of(DISBURSING, CANCELLED),
            // 入金补救回 ACTIVE；核销/结清同 R04：DELINQUENT → WRITTEN_OFF / SETTLED
            DELINQUENT, Set.of(ACTIVE, WRITTEN_OFF, SETTLED),
            SETTLED, Set.of(),
            EARLY_SETTLED, Set.of(),
            WRITTEN_OFF, Set.of(),
            CANCELLED, Set.of());

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    public boolean canTransitionTo(LoanStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /**
     * 领域状态图（<b>唯一事实源</b>）：状态 → 允许迁移到的后继状态集合。
     *
     * <p>供 Spring StateMachine 引擎生成迁移边——引擎侧不再另写一份状态图，
     * 因此不存在「两份图漂移」的可能。本方法只暴露既有迁移表，<b>零 Spring 依赖</b>
     * （domain 层纪律：零 Spring 注解、纯 JUnit 可跑）。</p>
     */
    public static Map<LoanStatus, Set<LoanStatus>> graph() {
        return ALLOWED;
    }
}
