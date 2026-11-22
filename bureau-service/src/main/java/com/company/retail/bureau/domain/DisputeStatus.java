package com.company.retail.bureau.domain;

/**
 * 征信异议工单状态（R06 integration.bureau_disputes.status）。
 *
 * <p>SLA 30 日（C-05 裁决值，retail.bureau.dispute-sla-days 可配）；
 * 超期处理 → {@code RF_BUREAU_002}（合规升级）。</p>
 */
public enum DisputeStatus {
    /** 已受理待处理。 */
    OPEN,
    /** 处理完成（订正报送留痕在 message log）。 */
    RESOLVED
}
