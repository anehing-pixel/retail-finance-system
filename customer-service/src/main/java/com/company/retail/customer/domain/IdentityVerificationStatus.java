package com.company.retail.customer.domain;

import com.company.retail.domain.exception.RFErrorCode;
import com.company.platform.exception.BusinessException;

import java.util.Set;

/**
 * 身份核验状态机（R04 §3.2）：PENDING → DOCUMENTS_RECEIVED → VERIFYING → VERIFIED / REJECTED。
 *
 * <p>C-07 裁决：文件 + 人工核验起步（eKYC 端口预留不实现）。
 * {@code REJECTED} 可重新发起新的一次核验（新 verification_id），不改旧记录。</p>
 */
public enum IdentityVerificationStatus {

    PENDING,
    DOCUMENTS_RECEIVED,
    VERIFYING,
    VERIFIED,
    REJECTED;

    /** 终态：不可再迁移（R04 §3.2，I-7）。 */
    public static final Set<IdentityVerificationStatus> TERMINAL = Set.of(VERIFIED, REJECTED);

    /** 终态不可再迁移；非终态只允许声明过的直接后继。 */
    public boolean canTransitionTo(IdentityVerificationStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case PENDING -> target == DOCUMENTS_RECEIVED;
            case DOCUMENTS_RECEIVED -> target == VERIFYING;
            case VERIFYING -> target == VERIFIED || target == REJECTED;
            case VERIFIED, REJECTED -> false;
        };
    }

    /** 迁移守卫：非法迁移抛 RF_VALID_002（R09 §4）。 */
    public void assertTransitionTo(IdentityVerificationStatus target) {
        if (!canTransitionTo(target)) {
            throw new BusinessException(RFErrorCode.RF_VALID_002, java.util.Map.of(
                    "from", name(), "to", String.valueOf(target)));
        }
    }
}
