package com.company.retail.titlerelease.domain;

import java.util.Map;
import java.util.Set;

/**
 * 解除订单状态机（R04 §3.5 {@code ReleaseOrderStatus}；解除八步 R05 §6）。
 *
 * <p>硬规则：</p>
 * <ul>
 *   <li>{@code CREATED} 要求债权终态（INV-3：Loan ∈ {SETTLED, EARLY_SETTLED}）——服务层守卫；</li>
 *   <li>{@code DOCUMENTS_ISSUED} 只是「文件出具」，<b>不得</b>改写保留状态（两态分离，ADR-RF-006）；</li>
 *   <li>{@code MANUAL_RECORDED}（我方见证代录）<b>不得直达</b> {@code REGISTRATION_CONFIRMED}——
 *       推进仍须外部回执存在（C-11：外部回执唯一权威）；</li>
 *   <li>终态：CLOSED / REJECTED / EXPIRED（I-7 不可再迁移）。</li>
 * </ul>
 */
public enum ReleaseOrderStatus {

    CREATED,
    DOCUMENTS_PREPARED,
    DOCUMENTS_ISSUED,
    EXTERNAL_SUBMITTED,
    MANUAL_RECORDED,
    REGISTRATION_CONFIRMED,
    CLOSED,
    REJECTED,
    EXPIRED;

    private static final Map<ReleaseOrderStatus, Set<ReleaseOrderStatus>> ALLOWED = Map.of(
            CREATED, Set.of(DOCUMENTS_PREPARED, REJECTED),
            DOCUMENTS_PREPARED, Set.of(DOCUMENTS_ISSUED, REJECTED),
            DOCUMENTS_ISSUED, Set.of(EXTERNAL_SUBMITTED, MANUAL_RECORDED, EXPIRED),
            EXTERNAL_SUBMITTED, Set.of(REGISTRATION_CONFIRMED, MANUAL_RECORDED, EXPIRED),
            MANUAL_RECORDED, Set.of(REGISTRATION_CONFIRMED, REJECTED),
            REGISTRATION_CONFIRMED, Set.of(CLOSED),
            CLOSED, Set.of(),
            REJECTED, Set.of(),
            EXPIRED, Set.of());

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    public boolean canTransitionTo(ReleaseOrderStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /**
     * 领域状态图（<b>唯一事实源</b>）：状态 → 允许迁移到的后继状态集合。
     *
     * <p>供 Spring StateMachine 引擎生成迁移边（引擎侧不另写一份状态图）；
     * 零 Spring 依赖，domain 层纪律不变。</p>
     */
    public static Map<ReleaseOrderStatus, Set<ReleaseOrderStatus>> graph() {
        return ALLOWED;
    }
}
