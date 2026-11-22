package com.company.retail.underwriting.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;

/**
 * 审查案件聚合根（A-5，BC-3；R04 §2 §3.2）。
 *
 * <p><b>不变式</b>（R04 §5.1）：</p>
 * <ul>
 *   <li>I-6：审查 OVERRIDE 与 DECIDED 不可同一自然人（SOD 硬阻断 {@code RF_AUTH_003}）；</li>
 *   <li>状态变更只能走本聚合方法（R00 §5），终态不可再迁移；</li>
 *   <li>查询未完成不得决定（U-3：状态未到 ASSESSING 即拒绝）。</li>
 * </ul>
 *
 * <p>本类零 Spring 注解，纯 JUnit 可测；异常用中台 {@link BusinessException}
 * （无框架依赖）+ 共享 domain 的 {@link RFErrorCode}。</p>
 */
public class UnderwritingCase {

    private Long caseId;
    private final long applicationId;
    /** 同意引用（决策时复核同意有效性的必填锚点，INV-5 / X-3）。 */
    private final long consentId;
    private UnderwritingStatus status;
    private DecisionOutcome outcome;
    private String decidedBy;
    private String reviewedBy;
    private java.time.OffsetDateTime decidedAt;
    private String ruleVersion;
    private int version;

    private UnderwritingCase(Long caseId, long applicationId, long consentId,
                             UnderwritingStatus status, DecisionOutcome outcome,
                             String decidedBy, String reviewedBy,
                             java.time.OffsetDateTime decidedAt, String ruleVersion, int version) {
        this.caseId = caseId;
        this.applicationId = applicationId;
        this.consentId = consentId;
        this.status = status;
        this.outcome = outcome;
        this.decidedBy = decidedBy;
        this.reviewedBy = reviewedBy;
        this.decidedAt = decidedAt;
        this.ruleVersion = ruleVersion;
        this.version = version;
    }

    /** 工厂：建立审查案件（G3 同意门禁由应用层在调用前 fail-closed 校验）。 */
    public static UnderwritingCase start(long applicationId, long consentId, String ruleVersion) {
        if (applicationId <= 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", "applicationId"));
        }
        if (consentId <= 0) {
            // INV-5：审查案件必须挂在有效同意上，缺失即拒绝
            throw new BusinessException(RFErrorCode.RF_UW_003, Map.of("reason", "CONSENT_REQUIRED"));
        }
        return new UnderwritingCase(null, applicationId, consentId,
                UnderwritingStatus.PENDING, null, null, null, null, ruleVersion, 0);
    }

    /** 重hydration：从仓储恢复（RowMapper 用）。 */
    public static UnderwritingCase restore(Long caseId, long applicationId, long consentId,
                                           String status, String outcome, String decidedBy,
                                           String reviewedBy, java.time.OffsetDateTime decidedAt,
                                           String ruleVersion, int version) {
        return new UnderwritingCase(caseId, applicationId, consentId,
                UnderwritingStatus.valueOf(status),
                outcome == null ? null : DecisionOutcome.valueOf(outcome),
                decidedBy, reviewedBy, decidedAt, ruleVersion, version);
    }

    /** 发起征信查询：PENDING / INQUIRY_FAILED（重试）→ INQUIRY_IN_PROGRESS。 */
    public void startInquiry() {
        transitionTo(UnderwritingStatus.INQUIRY_IN_PROGRESS);
    }

    /** 查询成功：INQUIRY_IN_PROGRESS → INQUIRY_DONE（不得跳过）。 */
    public void completeInquiry() {
        transitionTo(UnderwritingStatus.INQUIRY_DONE);
    }

    /** 查询失败：INQUIRY_IN_PROGRESS → INQUIRY_FAILED（留痕，不写空结果）。 */
    public void failInquiry() {
        transitionTo(UnderwritingStatus.INQUIRY_FAILED);
    }

    /** 进入评估：INQUIRY_DONE → ASSESSING。 */
    public void beginAssessment() {
        transitionTo(UnderwritingStatus.ASSESSING);
    }

    /**
     * 审查决定（G6）：ASSESSING → APPROVED / DECLINED / REFERRED，决定留痕。
     *
     * @return 决定结果（供应用层判断发布哪个事件）
     */
    public DecisionOutcome decide(String decidedBy, DecisionOutcome outcome, String ruleVersion) {
        requireNotBlankActor(decidedBy, "decidedBy");
        transitionTo(UnderwritingStatus.valueOf(outcome.name()));
        this.decidedBy = decidedBy;
        this.outcome = outcome;
        this.decidedAt = java.time.OffsetDateTime.now();
        if (ruleVersion != null && !ruleVersion.isBlank()) {
            this.ruleVersion = ruleVersion;
        }
        return outcome;
    }

    /**
     * 人工复核 / override（G6 + I-6）：REFERRED → APPROVED / DECLINED。
     * <b>SOD 硬阻断</b>：复核人 ≠ 原决定人（同一自然人 {@code RF_AUTH_003}，
     * 且不可用配置白名单豁免，R08 RB-7）。
     */
    public void overrideDecision(String reviewedBy, DecisionOutcome outcome) {
        requireNotBlankActor(reviewedBy, "reviewedBy");
        if (Objects.equals(reviewedBy, this.decidedBy)) {
            throw new BusinessException(RFErrorCode.RF_AUTH_003, Map.of(
                    "reason", "SOD_SELF_OVERRIDE",
                    "decidedBy", String.valueOf(decidedBy),
                    "reviewedBy", reviewedBy));
        }
        transitionTo(UnderwritingStatus.valueOf(outcome.name()));
        this.reviewedBy = reviewedBy;
        this.outcome = outcome;
        this.decidedAt = java.time.OffsetDateTime.now();
    }

    /** 承認有効期已过：APPROVED → EXPIRED（终态）。 */
    public void expire() {
        transitionTo(UnderwritingStatus.EXPIRED);
    }

    /** 状态迁移唯一入口：非法迁移 / 终态再迁移一律 RF_VALID_002。 */
    private void transitionTo(UnderwritingStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(RFErrorCode.RF_VALID_002, Map.of(
                    "reason", "ILLEGAL_TRANSITION",
                    "from", status.name(),
                    "to", target.name()));
        }
        this.status = target;
    }

    private void requireNotBlankActor(String actor, String field) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", field));
        }
    }

    public Long getCaseId() { return caseId; }
    public void assignId(Long caseId) { this.caseId = Objects.requireNonNull(caseId); }
    public long getApplicationId() { return applicationId; }
    public long getConsentId() { return consentId; }
    public UnderwritingStatus getStatus() { return status; }
    public DecisionOutcome getOutcome() { return outcome; }
    public String getDecidedBy() { return decidedBy; }
    public String getReviewedBy() { return reviewedBy; }
    public java.time.OffsetDateTime getDecidedAt() { return decidedAt; }
    public String getRuleVersion() { return ruleVersion; }
    public int getVersion() { return version; }
    public void bumpVersion() { this.version++; }
}
