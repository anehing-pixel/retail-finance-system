package com.company.retail.servicing.domain;

import java.util.Map;
import java.util.Set;

/**
 * 催收案件状态机（R04 A-13 聚合；表 core.collection_cases，V9）。
 *
 * <p>流转（分案 / 联系 / 承诺 / 结果流转，R04 §3 口径在本波次的落定）：</p>
 * <pre>
 *   OPEN ──分案──> ASSIGNED ──联系──> IN_CONTACT ──承诺──> PROMISED
 *                    │                │    ▲                 │
 *                    │                │    └──承诺未履约─────┘
 *                    └──────── 结果流转（任一在途状态 → CLOSED，须结果）──>
 *   终态：CLOSED（结果 REPAID / UNRECOVERABLE，显式登记，不物理删除）
 * </pre>
 *
 * <p>状态变更只能走聚合方法（R00 §5）；终态集合显式且不可再迁移（I-7）。</p>
 */
public enum CollectionCaseStatus {

    OPEN,
    ASSIGNED,
    IN_CONTACT,
    PROMISED,
    CLOSED;

    private static final Map<CollectionCaseStatus, Set<CollectionCaseStatus>> ALLOWED = Map.of(
            OPEN, Set.of(ASSIGNED, CLOSED),
            ASSIGNED, Set.of(IN_CONTACT, CLOSED),
            IN_CONTACT, Set.of(PROMISED, CLOSED),
            PROMISED, Set.of(IN_CONTACT, CLOSED),
            CLOSED, Set.of());

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    public boolean canTransitionTo(CollectionCaseStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
