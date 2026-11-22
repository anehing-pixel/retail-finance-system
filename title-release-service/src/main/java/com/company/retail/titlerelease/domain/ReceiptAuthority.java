package com.company.retail.titlerelease.domain;

/**
 * 回执权威分级（R04 §4 硬规则 3 / C-11 裁决：外部回执唯一权威）。
 *
 * <ul>
 *   <li>{@code EXTERNAL_REGISTRY}——运输支局 / 代办机构登记完成回执，<b>唯一</b>能推进
 *       {@code REGISTRATION_CONFIRMED} 与 {@code TitleRetention.RELEASE_REGISTERED} 的权威；</li>
 *   <li>{@code INTERNAL_WITNESS}——我方见证代录，仅能落 {@code MANUAL_RECORDED}（中间态，X-9）；</li>
 *   <li>{@code PARTNER_SELF_REPORT}——经销商/消费者自证，<b>不得采信</b>（X-10），一律拒绝。</li>
 * </ul>
 */
public enum ReceiptAuthority {

    EXTERNAL_REGISTRY,
    INTERNAL_WITNESS,
    PARTNER_SELF_REPORT;

    /** 是否可推进解押完成（C-11：仅外部回执）。 */
    public boolean canConfirmRegistration() {
        return this == EXTERNAL_REGISTRY;
    }

    /** 是否允许落中间态（我方见证代录）。 */
    public boolean canRecordManual() {
        return this == INTERNAL_WITNESS;
    }
}
