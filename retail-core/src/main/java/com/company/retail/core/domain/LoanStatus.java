package com.company.retail.core.domain;

import java.util.Map;
import java.util.Set;

/**
 * 债权状态机（R04 §3.4 本波次子集）。
 *
 * <p>与 R04 的对应：R04 的 {@code CREATED} 在 V6 迁移落定为
 * {@code PENDING_DISBURSEMENT}（建表默认值口径，W2 冻结）——两者是同一状态的不同名，
 * 本枚举以 V6 字面为准，其余状态与 R04 一致。</p>
 *
 * <p>终态四分（B-7）：SETTLED / EARLY_SETTLED / WRITTEN_OFF / CANCELLED 互斥；
 * 本波次只接 CANCELLED，结清/核销归 W6。</p>
 */
public enum LoanStatus {

    PENDING_DISBURSEMENT,
    DISBURSING,
    ACTIVE,
    DISBURSE_FAILED,
    CANCELLED;

    /** 迁移表（R04 §3.4 子集；终态集合显式为空，I-7）。 */
    private static final Map<LoanStatus, Set<LoanStatus>> ALLOWED = Map.of(
            PENDING_DISBURSEMENT, Set.of(DISBURSING, CANCELLED),
            DISBURSING, Set.of(ACTIVE, DISBURSE_FAILED),
            ACTIVE, Set.of(),                 // DELINQUENT/SETTLED/EARLY_SETTLED/WRITTEN_OFF 归 W6
            DISBURSE_FAILED, Set.of(DISBURSING, CANCELLED),
            CANCELLED, Set.of());

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    public boolean canTransitionTo(LoanStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
