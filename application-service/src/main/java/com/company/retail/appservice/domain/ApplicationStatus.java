package com.company.retail.appservice.domain;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;

import java.util.Map;
import java.util.Set;

/**
 * 申请状态机（R04 §3.1 穷举）：迁移、终态、守卫全在此。
 * 状态变更只能走 {@link Application} 聚合方法，禁止直接 UPDATE status（R00 §5）。
 */
public enum ApplicationStatus {

    DRAFT, SUBMITTED, DOCUMENTS_PENDING, VERIFIED, UNDER_REVIEW,
    REFERRED, APPROVED, CONTRACTING, CONTRACTED, DECLINED, WITHDRAWN, CANCELLED, EXPIRED;

    /** 终态集合（显式且互斥，I-7 / AGENTS B-7）。 */
    public static final Set<ApplicationStatus> TERMINAL =
            Set.of(DECLINED, WITHDRAWN, CANCELLED, EXPIRED, CONTRACTED);

    /** R04 §3.1 允许迁移表。 */
    public boolean canTransitionTo(ApplicationStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case DRAFT -> target == SUBMITTED;
            case SUBMITTED -> target == DOCUMENTS_PENDING || target == WITHDRAWN;
            case DOCUMENTS_PENDING -> target == VERIFIED || target == CANCELLED;
            case VERIFIED -> target == UNDER_REVIEW;
            case UNDER_REVIEW -> target == APPROVED || target == DECLINED || target == REFERRED;
            case REFERRED -> target == APPROVED || target == DECLINED;
            case APPROVED -> target == CONTRACTING || target == EXPIRED;
            case CONTRACTING -> target == CONTRACTED || target == CANCELLED;
            case DECLINED, WITHDRAWN, CANCELLED, EXPIRED, CONTRACTED -> false;
        };
    }

    /** 迁移守卫：非法迁移抛 RF_APP_002（R09 §4）。 */
    public void assertTransitionTo(ApplicationStatus target) {
        if (!canTransitionTo(target)) {
            throw new BusinessException(RFErrorCode.RF_APP_002, Map.of(
                    "from", name(), "to", String.valueOf(target)));
        }
    }
}
