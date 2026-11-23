package com.company.retail.customer.app;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeCipher;
import com.company.retail.common.messaging.EnvelopePublisher;
import com.company.retail.common.messaging.EnvelopeTopics;
import com.company.retail.customer.domain.IdentityVerification;
import com.company.retail.customer.domain.IdentityVerificationStatus;
import com.company.retail.customer.integration.KycProvider;
import com.company.retail.customer.repo.IdentityVerificationRepository;
import com.company.retail.customer.repo.PiiRepository;
import com.company.retail.domain.exception.RFErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * 身份核验用例（C-07：材料提交 + 人工审核动作流转状态；W-SIM2 双模——配置
 * {@code retail.kyc.provider-url} 时 start 会同步向 eKYC 供应商提交受理（只出脱敏号/引用，
 * 红线 B-7），providerRef 留痕进 result_snapshot；人工复核通道并行保留）。
 * VERIFIED 时发布 IDENTITY_VERIFIED（聚合键 verificationId，R04 §6）。
 *
 * <p><b>ADR-RF-014 削峰分支</b>：MQ 开启时，材料登记改为「封密文信封入队」，由消费端
 * {@link com.company.retail.customer.messaging.IdentityDocumentEnvelopeConsumer} 解密后执行
 * <b>同一个</b> {@link IdentityDocumentHandler} 动作（同步与异步只此一份业务逻辑）；
 * 通道未启用时行为与原来逐字一致（事务边界、状态推进均不变）。</p>
 */
@Service
public class IdentityVerificationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityVerificationService.class);

    private final IdentityVerificationRepository verifications;
    private final PiiRepository piiRepository;
    private final OutboxPublisher outbox;
    /** eKYC 供应商（W-SIM2 双模：null=未配置，纯人工 MANUAL）。 */
    private final KycProvider kycProvider;
    /** 材料登记处理器（同步路径与 MQ 消费端共用同一份业务动作，ADR-RF-014）。 */
    private final IdentityDocumentHandler documentHandler;
    /** MQ 通道（ADR-RF-014：缺 bean 或未启用 → 走同步路径，行为与原来逐字一致）。 */
    private final ObjectProvider<EnvelopePublisher> publisherProvider;
    private final ObjectProvider<EnvelopeCipher> cipherProvider;

    public IdentityVerificationService(IdentityVerificationRepository verifications,
                                       PiiRepository piiRepository,
                                       OutboxPublisher outbox,
                                       ObjectProvider<KycProvider> kycProvider,
                                       IdentityDocumentHandler documentHandler,
                                       ObjectProvider<EnvelopePublisher> publisherProvider,
                                       ObjectProvider<EnvelopeCipher> cipherProvider) {
        this.verifications = verifications;
        this.piiRepository = piiRepository;
        this.outbox = outbox;
        this.kycProvider = kycProvider.getIfAvailable();
        this.documentHandler = documentHandler;
        this.publisherProvider = publisherProvider;
        this.cipherProvider = cipherProvider;
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
        IdentityDocumentHandler.DocumentCommand command = new IdentityDocumentHandler.DocumentCommand(
                verificationId, documentType, attachmentId, expiryDate, maskedNumber, traceId);
        // 削峰分支（ADR-RF-014）：已入队 → 控制器立即返回，不做同步登记
        if (enqueueDocument(command)) {
            return;
        }
        // 同步路径：材料登记 + 状态推进（PENDING → DOCUMENTS_RECEIVED；补件不迁移）
        documentHandler.register(command);
    }

    /**
     * 削峰分支：把材料登记载荷封成密文信封投进 MQ（ADR-RF-014）。
     *
     * <p>载荷含脱敏号与证件有效期（个人信息），只存在于密文内部；broker 上只有信封。</p>
     *
     * @return true = 已入队（调用方跳过同步登记）；false = MQ 未启用（调用方走同步路径）
     */
    private boolean enqueueDocument(IdentityDocumentHandler.DocumentCommand command) {
        EnvelopePublisher publisher = publisherProvider == null ? null : publisherProvider.getIfAvailable();
        if (publisher == null || !publisher.enabled()) {
            return false;
        }
        EnvelopeCipher cipher = cipherProvider == null ? null : cipherProvider.getIfAvailable();
        if (cipher == null) {
            throw new IllegalStateException("MQ 通道已启用但信封加解密器缺失，拒绝降级为同步登记");
        }
        CipherEnvelope envelope = cipher.seal(EnvelopeTopics.IDENTITY_DOCUMENT,
                String.valueOf(command.verificationId()),
                IdentityDocumentHandler.payloadJson(command), command.traceId());
        publisher.publish(EnvelopeTopics.IDENTITY_DOCUMENT, envelope);
        log.info("核验材料已封密文入队 verificationId={} documentType={} envelopeId={}",
                command.verificationId(), command.documentType(), envelope.envelopeId());
        return true;
    }

    @Transactional
    public void start(long verificationId) {
        IdentityVerification agg = verifications.load(verificationId);
        agg.startVerification();
        // eKYC 供应商受理提交（W-SIM2：只出脱敏号/引用；失败/未配置→null，流程不中断）
        String providerSnapshot = null;
        if (kycProvider != null && !agg.getDocuments().isEmpty()) {
            IdentityVerification.Document doc = agg.getDocuments().get(agg.getDocuments().size() - 1);
            KycProvider.KycSubmission submission = kycProvider.submit(
                    verificationId, doc.maskedNumber(), doc.documentType());
            if (submission != null && submission.providerRef() != null
                    && !submission.providerRef().isBlank()) {
                providerSnapshot = "{\"provider\":\"" + submission.providerName()
                        + "\",\"providerRef\":\"" + submission.providerRef()
                        + "\",\"providerStatus\":\"" + submission.status() + "\"}";
            }
        }
        verifications.updateStatus(verificationId, agg.getStatus(), null, null, providerSnapshot);
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
