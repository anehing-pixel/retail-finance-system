package com.company.retail.customer.domain;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 身份核验聚合（A-2，R04 §2）：材料提交 → 人工审核动作流转状态（C-07 挡板）。
 *
 * <p>不变式：核验完成（VERIFIED）前不得进入审查（R04 §2 A-2）；
 * 文件有效期必须晚于当前时间（RF_IDV_001）；状态变更只能走聚合方法（R00 §5）。
 * 纯领域对象，零 Spring（R04 §5）。</p>
 */
public class IdentityVerification {

    /** 核验材料（引用 core.attachments，本地文件路径，ADR-RF-008）。 */
    public record Document(long documentId, String documentType, Long attachmentId,
                           java.time.LocalDate expiryDate, String maskedNumber) {
    }

    private final long verificationId;
    private final long customerId;
    private IdentityVerificationStatus status;
    private final List<Document> documents = new ArrayList<>();
    private Instant verifiedAt;
    private String reviewerId;

    public IdentityVerification(long verificationId, long customerId, IdentityVerificationStatus status) {
        this.verificationId = verificationId;
        this.customerId = customerId;
        this.status = status;
    }

    /** 仓储装载用：回放既有材料，不触发状态迁移与校验。 */
    public void restoreDocument(Document document) {
        documents.add(document);
    }

    /** 提交核验材料：PENDING / DOCUMENTS_RECEIVED 状态下可继续补件。 */
    public void submitDocument(Document document, java.time.LocalDate today) {
        if (IdentityVerificationStatus.TERMINAL.contains(status) || status == IdentityVerificationStatus.VERIFYING) {
            throw new BusinessException(RFErrorCode.RF_IDV_001, java.util.Map.of(
                    "reason", "状态不允许补件", "status", status.name()));
        }
        if (document == null || document.documentType() == null || document.documentType().isBlank()) {
            throw new BusinessException(RFErrorCode.RF_IDV_001, java.util.Map.of("reason", "材料类型缺失"));
        }
        // 文件有效期校验（A-2 不变式）：过期文件不算有效材料
        if (document.expiryDate() != null && today != null && document.expiryDate().isBefore(today)) {
            throw new BusinessException(RFErrorCode.RF_IDV_001, java.util.Map.of(
                    "reason", "文件已过期", "expiryDate", document.expiryDate().toString()));
        }
        documents.add(document);
        if (status == IdentityVerificationStatus.PENDING) {
            status = IdentityVerificationStatus.DOCUMENTS_RECEIVED;
        }
    }

    /** 进入核验中：至少一份有效材料。 */
    public void startVerification() {
        status.assertTransitionTo(IdentityVerificationStatus.VERIFYING);
        if (documents.isEmpty()) {
            throw new BusinessException(RFErrorCode.RF_IDV_001, java.util.Map.of("reason", "材料不齐"));
        }
        status = IdentityVerificationStatus.VERIFYING;
    }

    /** 人工审核通过（C-07：文件 + 人工起步）。 */
    public void verify(String reviewerId, Instant at) {
        status.assertTransitionTo(IdentityVerificationStatus.VERIFIED);
        if (reviewerId == null || reviewerId.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, java.util.Map.of("reason", "审核人必填"));
        }
        this.status = IdentityVerificationStatus.VERIFIED;
        this.reviewerId = reviewerId;
        this.verifiedAt = at;
    }

    /** 人工审核拒绝；旧记录不改写（终态），重新核验走新 ID。 */
    public void reject(String reviewerId) {
        status.assertTransitionTo(IdentityVerificationStatus.REJECTED);
        this.status = IdentityVerificationStatus.REJECTED;
        this.reviewerId = reviewerId;
    }

    public long getVerificationId() {
        return verificationId;
    }

    public long getCustomerId() {
        return customerId;
    }

    public IdentityVerificationStatus getStatus() {
        return status;
    }

    public List<Document> getDocuments() {
        return Collections.unmodifiableList(documents);
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public String getReviewerId() {
        return reviewerId;
    }
}
