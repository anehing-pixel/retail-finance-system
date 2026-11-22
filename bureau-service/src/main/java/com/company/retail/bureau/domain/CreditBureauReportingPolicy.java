package com.company.retail.bureau.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 策略点：征信报送口径（ADR-RF-002 四策略点之一，上下文 reporting，C-05）。
 *
 * <p><b>C-05 裁决值</b>（R01 §6）：CIC + JICC 双机构成员，查询双机构，报送主 CIC；
 * 报送 T+1（{@code retail.bureau.report-cron}）；异议 SLA 30 日（可配）。
 * 机构成员类别与接口待核实——全部走配置覆盖，不写死法务结论。</p>
 *
 * <p>DPD 映射口径「另定」不沿用 floorplan（ADR-RF-005 §6）：本策略点只管
 * 机构组合 / 周期 / SLA，报文字段映射在 ACL 适配器。</p>
 */
public interface CreditBureauReportingPolicy {

    /** 策略版本（留痕与排障用）。 */
    String policyVersion();

    /** 策略点开关：关闭时查询/报送全部转人工通道（不静默放行）。 */
    boolean enabled();

    /** 查询机构组合（C-05：CIC + JICC 双机构）。 */
    List<CreditBureau> inquiryTargets();

    /** 报送主机构（C-05：主 CIC）。 */
    CreditBureau primaryReportBureau();

    /** 异议处理 SLA 天数（C-05 裁决 30 日，可配置覆盖）。 */
    int disputeSlaDays();

    /** 报送业务日期口径：T+1（当日受理次日报送，cron 由批处理承载）。 */
    LocalDate nextReportBizDate(LocalDate receivedDate);
}
