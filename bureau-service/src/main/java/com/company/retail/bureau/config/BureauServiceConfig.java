package com.company.retail.bureau.config;

import com.company.retail.bureau.application.BureauDisputeRepository;
import com.company.retail.bureau.application.BureauDisputeAppService;
import com.company.retail.bureau.application.BureauMessageLogRepository;
import com.company.retail.bureau.application.BureauReportAppService;
import com.company.retail.bureau.application.BureauReportRepository;
import com.company.retail.bureau.application.ConsentGateGuard;
import com.company.retail.bureau.application.CreditInquiryAppService;
import com.company.retail.bureau.application.CreditInquiryRepository;
import com.company.retail.bureau.acl.BureauAdapter;
import com.company.retail.bureau.acl.CicStubAdapter;
import com.company.retail.bureau.acl.JiccStubAdapter;
import com.company.retail.bureau.domain.CreditBureau;
import com.company.retail.bureau.domain.CreditBureauReportingPolicy;
import com.company.retail.bureau.domain.DefaultCreditBureauReportingPolicy;
import com.company.retail.bureau.infrastructure.JdbcBureauDisputeRepository;
import com.company.retail.bureau.infrastructure.JdbcBureauMessageLogRepository;
import com.company.retail.bureau.infrastructure.JdbcBureauReportRepository;
import com.company.retail.bureau.infrastructure.JdbcCreditInquiryRepository;
import com.company.retail.common.contract.ConsentGateFacade;
import com.company.retail.common.contract.OutboxPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 征信防腐层精确装配（ADR-RF-005；挡板适配器 + C-05 裁决值策略点 + fail-closed 同意门禁）。
 */
@Configuration
public class BureauServiceConfig {

    @Bean
    public CreditInquiryRepository creditInquiryRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcCreditInquiryRepository(jdbcTemplate);
    }

    @Bean
    public BureauReportRepository bureauReportRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcBureauReportRepository(jdbcTemplate);
    }

    @Bean
    public BureauDisputeRepository bureauDisputeRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcBureauDisputeRepository(jdbcTemplate);
    }

    @Bean
    public BureauMessageLogRepository bureauMessageLogRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcBureauMessageLogRepository(jdbcTemplate);
    }

    /** 挡板适配器注册表（真实接入按机构各替换一个实现，接口不变）。 */
    @Bean
    public Map<CreditBureau, BureauAdapter> bureauAdapters() {
        List<BureauAdapter> list = List.of(new CicStubAdapter(), new JiccStubAdapter());
        return list.stream().collect(Collectors.toUnmodifiableMap(BureauAdapter::bureau, Function.identity()));
    }

    /** 策略点（C-05 裁决值 + 配置覆盖，P-12）。 */
    @Bean
    public CreditBureauReportingPolicy creditBureauReportingPolicy(
            @Value("${retail.bureau.enabled:true}") boolean enabled,
            @Value("${retail.underwriting.bureau-combination:CIC,JICC}") String bureauCombination,
            @Value("${retail.bureau.primary-report-bureau:CIC}") String primaryReportBureau,
            @Value("${retail.bureau.dispute-sla-days:30}") int disputeSlaDays,
            @Value("${retail.bureau.report-lead-days:1}") int reportLeadDays) {
        return new DefaultCreditBureauReportingPolicy(
                enabled, bureauCombination, primaryReportBureau, disputeSlaDays, reportLeadDays);
    }

    @Bean
    public ConsentGateGuard consentGateGuard(ConsentGateFacade consentGateFacade) {
        return new ConsentGateGuard(consentGateFacade);
    }

    @Bean
    public CreditInquiryAppService creditInquiryAppService(
            CreditInquiryRepository inquiryRepository,
            BureauMessageLogRepository messageLogRepository,
            Map<CreditBureau, BureauAdapter> adapters,
            CreditBureauReportingPolicy reportingPolicy,
            ConsentGateGuard consentGateGuard,
            OutboxPublisher outboxPublisher) {
        return new CreditInquiryAppService(inquiryRepository, messageLogRepository, adapters,
                reportingPolicy, consentGateGuard, outboxPublisher);
    }

    @Bean
    public BureauReportAppService bureauReportAppService(
            BureauReportRepository reportRepository,
            BureauMessageLogRepository messageLogRepository,
            Map<CreditBureau, BureauAdapter> adapters,
            CreditBureauReportingPolicy reportingPolicy,
            ConsentGateGuard consentGateGuard,
            OutboxPublisher outboxPublisher) {
        return new BureauReportAppService(reportRepository, messageLogRepository, adapters,
                reportingPolicy, consentGateGuard, outboxPublisher);
    }

    @Bean
    public BureauDisputeAppService bureauDisputeAppService(
            BureauDisputeRepository disputeRepository,
            BureauMessageLogRepository messageLogRepository,
            Map<CreditBureau, BureauAdapter> adapters,
            CreditBureauReportingPolicy reportingPolicy) {
        return new BureauDisputeAppService(disputeRepository, messageLogRepository, adapters, reportingPolicy);
    }
}
