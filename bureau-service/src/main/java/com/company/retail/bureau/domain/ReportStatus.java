package com.company.retail.bureau.domain;

/**
 * 征信报送状态（R06 integration.credit_bureau_reports.status）。
 *
 * <p>失败处理 X-11：重试 + 保留失败报文 + 差异工单，不静默丢弃；
 * 报送周期 T+1（C-05 裁决值，retail.bureau.report-cron 可配）。</p>
 */
public enum ReportStatus {
    /** 待报送（含人工通道挂起）。 */
    PENDING,
    /** 报送成功（发 CREDIT_BUREAU_REPORT_SENT）。 */
    SENT,
    /** 报送被拒（保留失败报文，人工重触发，RF_BUREAU_001）。 */
    REJECTED
}
