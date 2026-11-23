package com.company.retail.customer.app;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.customer.domain.Consent;
import com.company.retail.customer.repo.ConsentRepository;
import com.company.retail.customer.repo.PiiRepository;
import com.company.retail.domain.exception.RFErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * 同意管理用例（A-3）：取得 / 撤回（发 CONSENT_WITHDRAWN，合规刚性 INV-5）/ 有效性查询。
 *
 * <p>下游门禁 provider 侧（frozen FeignContracts.ConsentGateClient）：
 * {@code GET /api/consents/{consentId}/disbursement-gate}，<b>fail-closed</b>——
 * 查不到 / 已撤回 / 已过期一律 {@code allowed=false}（R09 §7.1）。</p>
 */
@Service
public class ConsentService {

    private final ConsentRepository consents;
    private final PiiRepository piiRepository;
    private final OutboxPublisher outbox;

    public ConsentService(ConsentRepository consents, PiiRepository piiRepository,
                          OutboxPublisher outbox) {
        this.consents = consents;
        this.piiRepository = piiRepository;
        this.outbox = outbox;
    }

    /** 取得同意（append-only 新行；版本变更需重新取得，R04 §5.2）。 */
    @Transactional
    public long grant(long customerId, String purpose, String consentVersion, String channel,
                      Long evidenceAttachmentId, Instant expiresAt, String traceId) {
        if (purpose == null || purpose.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", "purpose"));
        }
        if (!piiRepository.exists(customerId)) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    Map.of("reason", "客户不存在", "customerId", String.valueOf(customerId)));
        }
        long consentId = consents.insert(customerId, purpose, consentVersion, channel,
                evidenceAttachmentId, expiresAt, traceId);
        RfEventPayloads.publish(outbox, OutboxPublisher.CONSENT_GRANTED,
                "consentId", String.valueOf(consentId), traceId,
                Map.of("status", Consent.Status.GRANTED.name()));
        return consentId;
    }

    /** 语义撤回（非物理删除）：consents 状态 + withdrawals 留痕 + 事件，同一本地事务。 */
    @Transactional
    public void withdraw(long consentId, String reason, String traceId) {
        Consent consent = consents.findById(consentId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        Map.of("reason", "同意记录不存在", "consentId", String.valueOf(consentId))));
        Instant at = Instant.now();
        consent.withdraw(at);
        consents.markWithdrawn(consentId, at);
        consents.insertWithdrawal(consentId, at, reason, traceId);
        RfEventPayloads.publish(outbox, OutboxPublisher.CONSENT_WITHDRAWN,
                "consentId", String.valueOf(consentId), traceId,
                Map.of("status", Consent.Status.WITHDRAWN.name(),
                        "reasonCode", reason == null ? "CUSTOMER_REQUEST" : reason));
    }

    /**
     * 代付门禁 provider 判定（fail-closed）：不存在/已撤回/已过期 → allowed=false。
     * 判定内核在 {@link com.company.retail.customer.domain.ConsentGatePolicy}（纯领域，可单测）。
     */
    @Transactional
    public com.company.retail.customer.domain.ConsentGatePolicy.GateDecision disbursementGate(long consentId) {
        Consent consent = consents.findById(consentId).orElse(null);
        return com.company.retail.customer.domain.ConsentGatePolicy.decide(consent, Instant.now());
    }

    /** 同意有效性查询（customerId+purpose 口径；下游按需消费）。 */
    @Transactional
    public Map<String, Object> validity(long customerId, String purpose) {
        return consents.findActiveByCustomerAndPurpose(customerId, purpose)
                .map(c -> Map.<String, Object>of(
                        "valid", true,
                        "consentId", c.getConsentId(),
                        "purpose", c.getPurpose(),
                        "consentVersion", c.getConsentVersion()))
                // fail-closed：查不到 = 无效（INV-5）
                .orElse(Map.of("valid", false, "reasonCode", "NO_ACTIVE_CONSENT"));
    }
}
