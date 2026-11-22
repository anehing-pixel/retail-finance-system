package com.company.retail.titlerelease.domain;

import java.util.Map;
import java.util.Set;

/**
 * 保留状态机（R04 §3.5 {@code TitleRetentionStatus}；两态分离 ADR-RF-006）。
 *
 * <p>V8 落库默认 {@code ACTIVE}（建表默认值口径，W2 冻结）——登记前的占位态。
 * 核心硬规则：{@code REGISTERED → RELEASE_REGISTERED} 的<b>唯一入口</b>是
 * {@code ReleaseOrder=REGISTRATION_CONFIRMED} 且回执权威 = EXTERNAL_REGISTRY（INV-3 / C-11）。</p>
 */
public enum TitleRetentionStatus {

    /** V8 默认占位态（登记前）。 */
    ACTIVE,
    PENDING_REGISTRATION,
    REGISTERED,
    CONTRACTUAL_ONLY,
    RELEASE_REGISTERED,
    NOT_REQUIRED,
    CANCELLED,
    CLOSED;

    private static final Map<TitleRetentionStatus, Set<TitleRetentionStatus>> ALLOWED = Map.of(
            ACTIVE, Set.of(REGISTERED, CONTRACTUAL_ONLY, CANCELLED),
            PENDING_REGISTRATION, Set.of(REGISTERED, CONTRACTUAL_ONLY, CANCELLED),
            REGISTERED, Set.of(RELEASE_REGISTERED),
            CONTRACTUAL_ONLY, Set.of(CLOSED),
            RELEASE_REGISTERED, Set.of(CLOSED),
            NOT_REQUIRED, Set.of(),
            CANCELLED, Set.of(),
            CLOSED, Set.of());

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    public boolean canTransitionTo(TitleRetentionStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
