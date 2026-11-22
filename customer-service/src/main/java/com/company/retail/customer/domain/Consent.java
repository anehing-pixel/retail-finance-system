package com.company.retail.customer.domain;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;

import java.time.Instant;

/**
 * 同意聚合（A-3，R04 §2）：取得 / 撤回，撤回立即生效（INV-5）。
 *
 * <p>状态机（R04 §3.2）：{@code GRANTED → WITHDRAWN / EXPIRED}（终态）；
 * 撤回不删除历史留痕（pii.consents append-only + pii.consent_withdrawals）。
 * 纯领域对象，零 Spring。</p>
 */
public class Consent {

    public enum Status {
        GRANTED, WITHDRAWN, EXPIRED
    }

    private final long consentId;
    private final long customerId;
    private final String purpose;
    private final String consentVersion;
    private Status status;
    private final Instant grantedAt;
    private final Instant expiresAt;

    public Consent(long consentId, long customerId, String purpose, String consentVersion,
                   Status status, Instant grantedAt, Instant expiresAt) {
        this.consentId = consentId;
        this.customerId = customerId;
        this.purpose = purpose;
        this.consentVersion = consentVersion;
        this.status = status;
        this.grantedAt = grantedAt;
        this.expiresAt = expiresAt;
    }

    /** 撤回：只有 GRANTED 可撤回；撤回即刻阻断后续查询/报送（INV-5）。 */
    public void withdraw(Instant at) {
        if (status == Status.WITHDRAWN) {
            throw new BusinessException(RFErrorCode.RF_VALID_002,
                    java.util.Map.of("reason", "同意已撤回", "consentId", String.valueOf(consentId)));
        }
        if (status == Status.EXPIRED) {
            throw new BusinessException(RFErrorCode.RF_VALID_002,
                    java.util.Map.of("reason", "同意已过期", "consentId", String.valueOf(consentId)));
        }
        this.status = Status.WITHDRAWN;
    }

    /**
     * 有效性判定（fail-closed 的领域内核，INV-5）：
     * 仅当 GRANTED 且未过期时有效；查不到、已撤回、已过期一律无效。
     */
    public boolean isValidAt(Instant at) {
        if (status != Status.GRANTED) {
            return false;
        }
        return expiresAt == null || expiresAt.isAfter(at);
    }

    public long getConsentId() {
        return consentId;
    }

    public long getCustomerId() {
        return customerId;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getConsentVersion() {
        return consentVersion;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
