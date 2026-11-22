package com.company.retail.titlerelease.domain;

/**
 * 解除订单聚合根（A-12 的 {@code ReleaseOrder}，R04 §2/§3.5；解除八步 R05 §6）。
 * 状态变更只走本类方法（R00 §5）。金额无关；两态分离：本聚合的推进不改保留状态。
 */
public class ReleaseOrder {

    private final Long releaseOrderId;
    private final long loanId;
    private final long titleId;
    private ReleaseOrderStatus status;
    private final String source;                     // EVENT（LOAN_SETTLED 消费）/ MANUAL（人工挡板）
    private final Long loanSettledEventId;           // 证据链①②③的锚点
    private String externalSubmissionRef;            // 证据链⑥
    private java.time.Instant confirmedAt;           // 外部回执确认时点
    private String confirmationAuthority;
    private final int version;

    private ReleaseOrder(Long releaseOrderId, long loanId, long titleId, ReleaseOrderStatus status,
                         String source, Long loanSettledEventId, String externalSubmissionRef,
                         java.time.Instant confirmedAt, String confirmationAuthority, int version) {
        this.releaseOrderId = releaseOrderId;
        this.loanId = loanId;
        this.titleId = titleId;
        this.status = status;
        this.source = source;
        this.loanSettledEventId = loanSettledEventId;
        this.externalSubmissionRef = externalSubmissionRef;
        this.confirmedAt = confirmedAt;
        this.confirmationAuthority = confirmationAuthority;
        this.version = version;
    }

    public static ReleaseOrder create(long loanId, long titleId, String source, Long loanSettledEventId) {
        return new ReleaseOrder(null, loanId, titleId, ReleaseOrderStatus.CREATED,
                source, loanSettledEventId, null, null, null, 0);
    }

    public static ReleaseOrder restore(Long releaseOrderId, long loanId, long titleId, String status,
                                       String source, Long loanSettledEventId, String externalSubmissionRef,
                                       java.time.Instant confirmedAt, String confirmationAuthority,
                                       int version) {
        return new ReleaseOrder(releaseOrderId, loanId, titleId, ReleaseOrderStatus.valueOf(status),
                source, loanSettledEventId, externalSubmissionRef, confirmedAt, confirmationAuthority,
                version);
    }

    /** 八步第 2 步守卫（INV-3）：订单只能建在债权终态之后。 */
    public static void assertCreationAllowed(boolean loanTerminalEvidence) {
        if (!loanTerminalEvidence) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_TITLE_001,
                    "非结清不得解除（INV-3：ReleaseOrder.CREATED 要求 Loan ∈ {SETTLED, EARLY_SETTLED}）");
        }
    }

    /** 第 3 步：备齐解除文件四件套 → DOCUMENTS_PREPARED。 */
    public void prepareDocuments() {
        transitionTo(ReleaseOrderStatus.DOCUMENTS_PREPARED);
    }

    /** 第 4 步：発行文件并交付 → DOCUMENTS_ISSUED（文件出具 ≠ 解押完成，两态分离）。 */
    public void issueDocuments() {
        transitionTo(ReleaseOrderStatus.DOCUMENTS_ISSUED);
    }

    /** 第 5 步：外部提交登记申请 → EXTERNAL_SUBMITTED（证据链⑥：申请受理编号必填）。 */
    public void submitExternal(String submissionRef) {
        if (submissionRef == null || submissionRef.isBlank()) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_VALID_001,
                    "外部申请受理编号必填（证据链⑥）");
        }
        transitionTo(ReleaseOrderStatus.EXTERNAL_SUBMITTED);
        this.externalSubmissionRef = submissionRef;
    }

    /**
     * 我方见证代录（X-9）：仅能落 {@code MANUAL_RECORDED}，<b>不是</b>解押完成（C-11）。
     */
    public void recordManualWitness() {
        transitionTo(ReleaseOrderStatus.MANUAL_RECORDED);
    }

    /**
     * 第 6 步：取得<b>外部</b>登记完成回执 → REGISTRATION_CONFIRMED。
     * 守卫（INV-3 / C-11）：只有 {@code authority.canConfirmRegistration()}（= EXTERNAL_REGISTRY）才可推进；
     * MANUAL_RECORDED 补外部回执后同样由此入口推进（R04 §3.5）。
     */
    public void confirmRegistration(ReceiptAuthority authority) {
        if (!authority.canConfirmRegistration()) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_TITLE_002,
                    "回执权威不足，不得推进解押完成（C-11：外部回执唯一权威；"
                            + authority + " 只能落 MANUAL_RECORDED 或被拒绝）",
                    java.util.Map.of("authority", authority.name()));
        }
        transitionTo(ReleaseOrderStatus.REGISTRATION_CONFIRMED);
        this.confirmedAt = java.time.Instant.now();
        this.confirmationAuthority = authority.name();
    }

    /** 第 8 步：关闭 → CLOSED（终态）。 */
    public void close() {
        transitionTo(ReleaseOrderStatus.CLOSED);
    }

    public void reject() {
        transitionTo(ReleaseOrderStatus.REJECTED);
    }

    private void transitionTo(ReleaseOrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_VALID_002,
                    "解除订单状态机不允许 " + status + " → " + target + "（R04 §3.5）",
                    java.util.Map.of("current", status.name(), "target", target.name()));
        }
        this.status = target;
    }

    public boolean hasExternalSubmissionEvidence() {
        return externalSubmissionRef != null && !externalSubmissionRef.isBlank();
    }

    /** 证据链⑦⑧判定（订单 + 回执权威）：外部回执存在且权威 = EXTERNAL_REGISTRY。 */
    public static boolean externalReceiptEvidence(ReceiptAuthority authority) {
        return authority.canConfirmRegistration();
    }

    public Long releaseOrderId() {
        return releaseOrderId;
    }

    public long loanId() {
        return loanId;
    }

    public long titleId() {
        return titleId;
    }

    public ReleaseOrderStatus status() {
        return status;
    }

    public String source() {
        return source;
    }

    public Long loanSettledEventId() {
        return loanSettledEventId;
    }

    public String externalSubmissionRef() {
        return externalSubmissionRef;
    }

    public java.time.Instant confirmedAt() {
        return confirmedAt;
    }

    public String confirmationAuthority() {
        return confirmationAuthority;
    }

    public int version() {
        return version;
    }
}
