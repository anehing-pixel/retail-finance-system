package com.company.retail.contract.domain;

/**
 * 法定书面文件交付留痕（A-8 的 {@code StatutoryDocument}，表 {@code core.statutory_documents}）。
 *
 * <p><b>C-08 裁决口径</b>：只做「交付动作留痕与状态记录」（时间/方式/模板版本/附件引用），
 * <b>不实现书面文件效力判断</b>——效力归法务意见书，系统只留痕。</p>
 *
 * <p>不变式（R06 §7）：{@code SIGNED} 前必须有交付行（{@code delivered_at} 非空）。</p>
 */
public class StatutoryDocument {

    /** 缔约前书面文件（《分期付款销售法》/《贷金业法》书面交付义务）。 */
    public static final String PRE_CONTRACT_STATEMENT = "PRE_CONTRACT_STATEMENT";
    /** 合同书（契约书面）。 */
    public static final String CONTRACT_DOCUMENT = "CONTRACT_DOCUMENT";

    private final Long docId;
    private final long contractId;
    private final String docType;
    private final String templateVersion;
    private final java.time.Instant deliveredAt;
    private final String deliveryMethod;
    private final Long attachmentId;
    private final java.time.Instant acknowledgedAt;

    public StatutoryDocument(Long docId, long contractId, String docType, String templateVersion,
                             java.time.Instant deliveredAt, String deliveryMethod,
                             Long attachmentId, java.time.Instant acknowledgedAt) {
        if (docType == null || docType.isBlank()) {
            throw new IllegalArgumentException("书面文件 docType 不能为空");
        }
        if (templateVersion == null || templateVersion.isBlank()) {
            throw new IllegalArgumentException("书面文件 templateVersion 不能为空（模板可配，版本必留痕）");
        }
        this.docId = docId;
        this.contractId = contractId;
        this.docType = docType;
        this.templateVersion = templateVersion;
        this.deliveredAt = deliveredAt;
        this.deliveryMethod = deliveryMethod;
        this.attachmentId = attachmentId;
        this.acknowledgedAt = acknowledgedAt;
    }

    /** 是否已完成交付留痕（G7 判定口径：delivered_at 存在即视为已留痕）。 */
    public boolean isDelivered() {
        return deliveredAt != null;
    }

    public Long docId() {
        return docId;
    }

    public long contractId() {
        return contractId;
    }

    public String docType() {
        return docType;
    }

    public String templateVersion() {
        return templateVersion;
    }

    public java.time.Instant deliveredAt() {
        return deliveredAt;
    }

    public String deliveryMethod() {
        return deliveryMethod;
    }

    public Long attachmentId() {
        return attachmentId;
    }

    public java.time.Instant acknowledgedAt() {
        return acknowledgedAt;
    }
}
