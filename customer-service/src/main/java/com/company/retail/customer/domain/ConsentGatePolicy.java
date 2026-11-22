package com.company.retail.customer.domain;

import java.time.Instant;

/**
 * 同意门禁判定（INV-5 领域内核，fail-closed）：下游代付门禁 provider 侧的判定纯函数。
 * 查不到（null）、已撤回、已过期一律 {@code allowed=false}（R09 §7.1）。
 */
public final class ConsentGatePolicy {

    private ConsentGatePolicy() {
    }

    /** 判定结果：与 frozen FeignContracts.GateResult JSON 同构（allowed/reasonCode）。 */
    public record GateDecision(boolean allowed, String reasonCode) {
    }

    public static GateDecision decide(Consent consent, Instant at) {
        if (consent == null) {
            return new GateDecision(false, "CONSENT_NOT_FOUND");
        }
        if (!consent.isValidAt(at)) {
            return new GateDecision(false,
                    consent.getStatus() == Consent.Status.WITHDRAWN ? "CONSENT_WITHDRAWN" : "CONSENT_EXPIRED");
        }
        return new GateDecision(true, null);
    }
}
