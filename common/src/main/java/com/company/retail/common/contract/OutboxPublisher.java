package com.company.retail.common.contract;

/**
 * outbox 发布端口 + 事件名常量表（语义收口，冻结源 R00 §3.6 / R09 §5.1）。
 *
 * <p><b>与 floorplan 的隔离</b>：事件常量是语义，不进中台；本项目在
 * {@code com.company.retail.common.contract} 自持一份（R00 §6 裁决），禁止互相引用。</p>
 *
 * <p><b>硬规则</b>（R00 §3.6）：</p>
 * <ul>
 *   <li>{@code aggregate_key} 一次发生一个值（见各常量注释的键），禁止裸 vin、禁止 traceId、
 *       禁止每次换新值——outbox 去重按 {@code (event_type, aggregate_key)} 判「同聚合已有事件」，</li>
 *   <li>payload 禁止任何个人信息：发布前过 {@link EventPayloadGuard} 白名单（INV-4，默认拒绝）。</li>
 * </ul>
 */
public interface OutboxPublisher extends com.company.platform.event.OutboxPublisher {

    /** BC-1 申请受理提交。聚合键 applicationId。 */
    String APPLICATION_SUBMITTED = "APPLICATION_SUBMITTED";
    /** BC-2 身份核验通过。聚合键 verificationId。 */
    String IDENTITY_VERIFIED = "IDENTITY_VERIFIED";
    /** BC-2 同意取得。聚合键 consentId。 */
    String CONSENT_GRANTED = "CONSENT_GRANTED";
    /** BC-2 同意撤回——合规刚性，必须秒级送达 BC-3/BC-4（INV-5）。聚合键 consentId。 */
    String CONSENT_WITHDRAWN = "CONSENT_WITHDRAWN";
    /** BC-4 征信查询完成。聚合键 inquiryId。 */
    String CREDIT_INQUIRY_COMPLETED = "CREDIT_INQUIRY_COMPLETED";
    /** BC-3 审查批准。聚合键 caseId。 */
    String UNDERWRITING_APPROVED = "UNDERWRITING_APPROVED";
    /** BC-3 审查拒绝。聚合键 caseId。 */
    String UNDERWRITING_DECLINED = "UNDERWRITING_DECLINED";
    /** BC-5 合同签订。聚合键 contractId。 */
    String CONTRACT_SIGNED = "CONTRACT_SIGNED";
    /** BC-6 代付完成。聚合键 disbursementId。 */
    String DISBURSEMENT_PAID = "DISBURSEMENT_PAID";
    /** BC-10 交车（销售店投影）。聚合键 applicationId。 */
    String VEHICLE_DELIVERED = "VEHICLE_DELIVERED";
    /** BC-8 所有权保留登记。聚合键 titleId。 */
    String TITLE_RETENTION_REGISTERED = "TITLE_RETENTION_REGISTERED";
    /** BC-7 入金到账。聚合键 paymentRef。 */
    String REPAYMENT_RECEIVED = "REPAYMENT_RECEIVED";
    /** BC-6 债权终态（结清）。聚合键 loanId。 */
    String LOAN_SETTLED = "LOAN_SETTLED";
    /** BC-8 解除文件出具——只能在债权终态之后（INV-3）。聚合键 releaseOrderId。 */
    String RELEASE_DOCUMENTS_ISSUED = "RELEASE_DOCUMENTS_ISSUED";
    /** BC-8 名义变更登记完成——必须有外部登记回执（INV-3）。聚合键 releaseOrderId。 */
    String TITLE_RELEASE_REGISTERED = "TITLE_RELEASE_REGISTERED";
    /** BC-9 逾期阶段变化。聚合键 loanId。 */
    String DPD_CHANGED = "DPD_CHANGED";
    /** BC-4 征信报送已发送。聚合键 reportId。 */
    String CREDIT_BUREAU_REPORT_SENT = "CREDIT_BUREAU_REPORT_SENT";

    /**
     * 事件 → 聚合键字段名映射（发布端校验用，与 R09 §5.1 的 aggregate_key 列一一对应）。
     * 新增事件必须同步登记，否则发布时被 {@code requireBizKeyAndTraceId} 拒绝。
     */
    static String bizKeyOf(String eventType) {
        return switch (eventType) {
            case APPLICATION_SUBMITTED, VEHICLE_DELIVERED -> "applicationId";
            case IDENTITY_VERIFIED -> "verificationId";
            case CONSENT_GRANTED, CONSENT_WITHDRAWN -> "consentId";
            case CREDIT_INQUIRY_COMPLETED -> "inquiryId";
            case UNDERWRITING_APPROVED, UNDERWRITING_DECLINED -> "caseId";
            case CONTRACT_SIGNED -> "contractId";
            case DISBURSEMENT_PAID -> "disbursementId";
            case TITLE_RETENTION_REGISTERED -> "titleId";
            case REPAYMENT_RECEIVED -> "paymentRef";
            case LOAN_SETTLED, DPD_CHANGED -> "loanId";
            case RELEASE_DOCUMENTS_ISSUED, TITLE_RELEASE_REGISTERED -> "releaseOrderId";
            case CREDIT_BUREAU_REPORT_SENT -> "reportId";
            default -> throw new IllegalArgumentException(
                    "未登记的事件类型（R09 §5.1）：先补 OutboxPublisher 常量与 bizKeyOf 映射再发布：" + eventType);
        };
    }
}
