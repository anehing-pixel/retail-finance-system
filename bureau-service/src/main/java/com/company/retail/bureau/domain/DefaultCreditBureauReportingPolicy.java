package com.company.retail.bureau.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * {@link CreditBureauReportingPolicy} 默认实现（C-05 AI 裁决值 + 配置覆盖，挡板口径）。
 *
 * <p>参数全部来自配置（P-12：口径变更走配置不改代码）：
 * {@code retail.underwriting.bureau-combination}（默认 {@code CIC,JICC}）、
 * {@code retail.bureau.report-cron-days}（默认 1 = T+1）、
 * {@code retail.bureau.dispute-sla-days}（默认 30）、
 * {@code retail.bureau.enabled}（策略点开关）。</p>
 */
public class DefaultCreditBureauReportingPolicy implements CreditBureauReportingPolicy {

    public static final String VERSION = "C05-DEFAULT-1.0";

    private final boolean enabled;
    private final List<CreditBureau> inquiryTargets;
    private final CreditBureau primaryReportBureau;
    private final int disputeSlaDays;
    private final int reportLeadDays;

    public DefaultCreditBureauReportingPolicy(boolean enabled,
                                              String bureauCombination,
                                              String primaryReportBureau,
                                              int disputeSlaDays,
                                              int reportLeadDays) {
        this.enabled = enabled;
        this.inquiryTargets = parseCombination(bureauCombination);
        this.primaryReportBureau = CreditBureau.valueOf(primaryReportBureau);
        this.disputeSlaDays = disputeSlaDays;
        this.reportLeadDays = reportLeadDays;
    }

    private static List<CreditBureau> parseCombination(String combination) {
        if (combination == null || combination.isBlank()) {
            throw new IllegalArgumentException("retail.underwriting.bureau-combination 不能为空（C-05）");
        }
        return List.of(combination.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(CreditBureau::valueOf)
                .toList();
    }

    @Override
    public String policyVersion() {
        return VERSION;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<CreditBureau> inquiryTargets() {
        return inquiryTargets;
    }

    @Override
    public CreditBureau primaryReportBureau() {
        return primaryReportBureau;
    }

    @Override
    public int disputeSlaDays() {
        return disputeSlaDays;
    }

    @Override
    public LocalDate nextReportBizDate(LocalDate receivedDate) {
        return receivedDate.plusDays(reportLeadDays);
    }
}
