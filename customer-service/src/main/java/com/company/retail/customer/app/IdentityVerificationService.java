package com.company.retail.customer.app;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.customer.domain.IdentityVerification;
import com.company.retail.customer.domain.IdentityVerificationStatus;
import com.company.retail.customer.repo.JdbcIdentityVerificationRepository;
import com.company.retail.customer.repo.JdbcPiiRepository;
import com.company.retail.domain.exception.RFErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * 身份核验用例（C-07 挡板：材料提交 + 人工审核动作流转状态，不接真实供应商）。
 * VERIFIED 时发布 IDENTITY_VERIFIED（聚合键 verificationId，R04 §6）。
 */
@Service
public class IdentityVerificationService {

    private final JdbcIdentityVerificationRepository verifications;
    private final JdbcPiiRepository piiRepository;
    private final OutboxPublisher outbox;

    public IdentityVerificationService(JdbcIdentityVerificationRepository verifications,
                                       JdbcPiiRepository piiRepository,
                                       OutboxPublisher outbox) {
        this.verifications = verifications;
        this.piiRepository = piiRepository;
        this.outbox = outbox;
    }

    @Transactional
    public long create(long customerId, String traceId) {
        if (!piiRepository.exists(customerId)) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    Map.of("reason", "客户不存在", "customerId", String.valueOf(customerId)));
        }
        return verifications.insert(customerId, "MANUAL", traceId);
    }

    /** 提交核验材料（挡板：只记录引用与脱敏号，不接供应商）。 */
    @Transactional
    public void submitDocument(long verificationId, String documentType, Long attachmentId,
                               LocalDate expiryDate, String maskedNumber, String traceId) {
        IdentityVerification agg = verifications.load(verificationId);
        agg.submitDocument(new IdentityVerification.Document(0, documentType, attachmentId,
                expiryDate, maskedNumber), LocalDate.now());
        verifications.insertDocument(verificationId, documentType, attachmentId, expiryDate, maskedNumber, traceId);
        // 状态随材料推进（PENDING → DOCUMENTS_RECEIVED）；补件不迁移
        verifications.updateStatus(verificationId, agg.getStatus(), null, null, null);
    }

    @Transactional
    public void start(long verificationId) {
        IdentityVerification agg = verifications.load(verificationId);
        agg.startVerification();
        verifications.updateStatus(verificationId, agg.getStatus(), null, null, null);
    }

    /** 人工审核动作（reviewerId=审核员工工号；结果快照 JSONB 留痕）。 */
    @Transactional
    public void review(long verificationId, boolean approve, String reviewerId, String traceId) {
        IdentityVerification agg = verifications.load(verificationId);
        if (approve) {
            agg.verify(reviewerId, Instant.now());
        } else {
            agg.reject(reviewerId);
        }
        String snapshot = "{\"outcome\":\"" + agg.getStatus().name() + "\",\"reviewer\":\"" + reviewerId + "\"}";
        verifications.updateStatus(verificationId, agg.getStatus(), agg.getVerifiedAt(), reviewerId, snapshot);
        if (approve) {
            // IDENTITY_VERIFIED（聚合键 verificationId；payload 只带白名单业务主键，禁 PII/customerId）
            RfEventPayloads.publish(outbox, OutboxPublisher.IDENTITY_VERIFIED,
                    "verificationId", String.valueOf(verificationId), traceId,
                    Map.of("status", agg.getStatus().name()));
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(long verificationId) {
        IdentityVerification agg = verifications.load(verificationId);
        return Map.of(
                "verificationId", agg.getVerificationId(),
                "customerId", agg.getCustomerId(),
                "status", agg.getStatus().name(),
                "documentCount", agg.getDocuments().size());
    }
}
