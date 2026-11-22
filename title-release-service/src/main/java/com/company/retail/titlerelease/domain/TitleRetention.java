package com.company.retail.titlerelease.domain;

/**
 * 保留聚合根（A-12 的 {@code TitleRetention}，R04 §2/§3.5）。
 *
 * <p>两态分离四条硬规则的落点：</p>
 * <ol>
 *   <li>解除文件出具（{@code ReleaseOrder.DOCUMENTS_ISSUED}）<b>不改</b>本聚合状态——本类
 *       根本不暴露该入口；</li>
 *   <li>{@code REGISTERED → RELEASE_REGISTERED} 唯一入口：
 *       {@code ReleaseOrder=REGISTRATION_CONFIRMED} 且回执权威 = EXTERNAL_REGISTRY（INV-3）；</li>
 *   <li>回执权威分级见 {@link ReceiptAuthority}（PARTNER_SELF_REPORT 不得采信）；</li>
 *   <li>未取得外部回执前，对外语义只能是「解除文件已出具」，不得说「解押完成」。</li>
 * </ol>
 */
public class TitleRetention {

    private final Long titleId;
    private final long loanId;
    private final String vin;
    private TitleRetentionStatus status;
    private final String registrationMode;           // REGISTERED / CONTRACTUAL_ONLY（C-04）
    private java.time.Instant registeredAt;
    private java.time.Instant releasedAt;
    private final int version;

    private TitleRetention(Long titleId, long loanId, String vin, TitleRetentionStatus status,
                           String registrationMode, java.time.Instant registeredAt,
                           java.time.Instant releasedAt, int version) {
        this.titleId = titleId;
        this.loanId = loanId;
        this.vin = vin;
        this.status = status;
        this.registrationMode = registrationMode;
        this.registeredAt = registeredAt;
        this.releasedAt = releasedAt;
        this.version = version;
    }

    /** S9 所有权保留登记（G9：交车报告 + 车辆检验合格证证据由上游保证；C-04 默认 REGISTERED）。 */
    public static TitleRetention register(long loanId, String vin, boolean contractualOnly) {
        if (vin == null || vin.isBlank()) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_VALID_001, "vin 必填");
        }
        return new TitleRetention(null, loanId, vin,
                contractualOnly ? TitleRetentionStatus.CONTRACTUAL_ONLY : TitleRetentionStatus.REGISTERED,
                contractualOnly ? "CONTRACTUAL_ONLY" : "REGISTERED",
                contractualOnly ? null : java.time.Instant.now(), null, 0);
    }

    /** 挡板兜底（LOAN_SETTLED 消费时缺登记记录的场景；W6 口径，见最终报告）。 */
    public static TitleRetention restoreDefaultForLoan(long loanId, String vin, int version) {
        return new TitleRetention(null, loanId, vin, TitleRetentionStatus.REGISTERED,
                "REGISTERED", java.time.Instant.now(), null, version);
    }

    public static TitleRetention restore(Long titleId, long loanId, String vin, String status,
                                         String registrationMode, java.time.Instant registeredAt,
                                         java.time.Instant releasedAt, int version) {
        return new TitleRetention(titleId, loanId, vin, TitleRetentionStatus.valueOf(status),
                registrationMode, registeredAt, releasedAt, version);
    }

    /**
     * 第 7 步：改写保留状态 → RELEASE_REGISTERED。
     * 双守卫（INV-3 / I-4）：订单已 REGISTRATION_CONFIRMED 且回执权威 = EXTERNAL_REGISTRY。
     */
    public void markReleaseRegistered(ReleaseOrderStatus orderStatus, ReceiptAuthority receiptAuthority) {
        if (orderStatus != ReleaseOrderStatus.REGISTRATION_CONFIRMED) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_TITLE_002,
                    "解除订单未到 REGISTRATION_CONFIRMED，不得改写保留状态（INV-3）",
                    java.util.Map.of("orderStatus", orderStatus.name()));
        }
        if (!receiptAuthority.canConfirmRegistration()) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_TITLE_002,
                    "回执权威不足，保留状态不得进入 RELEASE_REGISTERED（C-11）",
                    java.util.Map.of("authority", receiptAuthority.name()));
        }
        if (!status.canTransitionTo(TitleRetentionStatus.RELEASE_REGISTERED)) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_VALID_002,
                    "保留状态机不允许 " + status + " → RELEASE_REGISTERED",
                    java.util.Map.of("current", status.name()));
        }
        this.status = TitleRetentionStatus.RELEASE_REGISTERED;
        this.releasedAt = java.time.Instant.now();
    }

    /** 第 8 步：证据链闭合后关闭（终态）。 */
    public void close() {
        if (!status.canTransitionTo(TitleRetentionStatus.CLOSED)) {
            throw new IllegalStateException("保留状态机不允许 " + status + " → CLOSED");
        }
        this.status = TitleRetentionStatus.CLOSED;
    }

    public Long titleId() {
        return titleId;
    }

    public long loanId() {
        return loanId;
    }

    public String vin() {
        return vin;
    }

    public TitleRetentionStatus status() {
        return status;
    }

    public String registrationMode() {
        return registrationMode;
    }

    public java.time.Instant registeredAt() {
        return registeredAt;
    }

    public java.time.Instant releasedAt() {
        return releasedAt;
    }

    public int version() {
        return version;
    }
}
