package com.company.retail.bureau.config;

import com.company.platform.calendar.BusinessCalendar;
import com.company.retail.bureau.application.BureauBatchRunPort;
import com.company.retail.bureau.application.BureauDisputeRepository;
import com.company.retail.bureau.application.BureauDisputeAppService;
import com.company.retail.bureau.application.BureauMessageLogRepository;
import com.company.retail.bureau.application.BureauReportAppService;
import com.company.retail.bureau.application.BureauReportBatchJob;
import com.company.retail.bureau.application.BureauReportExecutionService;
import com.company.retail.bureau.application.BureauReportRepository;
import com.company.retail.bureau.application.BureauTriggerPort;
import com.company.retail.bureau.application.ConsentGateGuard;
import com.company.retail.bureau.application.CreditInquiryAppService;
import com.company.retail.bureau.application.CreditInquiryRepository;
import com.company.retail.bureau.application.ManualReportAppService;
import com.company.retail.bureau.application.SettlementConsentAnchorPort;
import com.company.retail.bureau.acl.BureauAdapter;
import com.company.retail.bureau.acl.CicStubAdapter;
import com.company.retail.bureau.acl.HttpCicAdapter;
import com.company.retail.bureau.acl.JiccStubAdapter;
import com.company.retail.bureau.domain.CreditBureau;
import com.company.retail.bureau.domain.CreditBureauReportingPolicy;
import com.company.retail.bureau.domain.DefaultCreditBureauReportingPolicy;
import com.company.retail.bureau.infrastructure.JdbcBureauBatchRunRepository;
import com.company.retail.bureau.infrastructure.JdbcBureauDisputeRepository;
import com.company.retail.bureau.infrastructure.JdbcBureauMessageLogRepository;
import com.company.retail.bureau.infrastructure.JdbcBureauReportRepository;
import com.company.retail.bureau.infrastructure.JdbcBureauTriggerRepository;
import com.company.retail.bureau.infrastructure.JdbcCreditInquiryRepository;
import com.company.retail.bureau.infrastructure.JdbcSettlementConsentAnchor;
import com.company.retail.common.contract.ConsentGateFacade;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.infrastructure.persistence.JdbcBusinessCalendar;
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

    /** 触发登记读取/状态推进（core.bureau_report_triggers 只读投影 + CONSUMED/BLOCKED 推进）。 */
    @Bean
    public BureauTriggerPort bureauTriggerPort(JdbcTemplate jdbcTemplate) {
        return new JdbcBureauTriggerRepository(jdbcTemplate);
    }

    /** 结清报送同意锚点解析（loans → contracts → underwriting_cases，跨域只读）。 */
    @Bean
    public SettlementConsentAnchorPort settlementConsentAnchorPort(JdbcTemplate jdbcTemplate) {
        return new JdbcSettlementConsentAnchor(jdbcTemplate);
    }

    /** 批处理留痕（core.batch_runs；uk_batch_job_date 同日重跑复用同一行）。 */
    @Bean
    public BureauBatchRunPort bureauBatchRunPort(JdbcTemplate jdbcTemplate) {
        return new JdbcBureauBatchRunRepository(jdbcTemplate);
    }

    /** 营业日历（core.holidays；报送日批的 T+1 营业日判定）。 */
    @Bean
    public BusinessCalendar bureauBusinessCalendar(JdbcTemplate jdbcTemplate) {
        return new JdbcBusinessCalendar(jdbcTemplate);
    }

    /**
     * 适配器注册表（双模装配，W-SIM）：CIC 按 {@code retail.bureau.cic-url} 切换——
     * 不配走挡板 CicStubAdapter，配了走 HTTP 适配器（外部机构模拟器 / 未来真实专线，
     * 同一提议协议 v1 报文）；JICC 维持挡板。真实接入按机构各替换一个实现，接口不变。
     */
    @Bean
    public Map<CreditBureau, BureauAdapter> bureauAdapters(
            @Value("${retail.bureau.cic-url:}") String cicUrl) {
        BureauAdapter cic = (cicUrl == null || cicUrl.isBlank())
                ? new CicStubAdapter() : new HttpCicAdapter(cicUrl);
        List<BureauAdapter> list = List.of(cic, new JiccStubAdapter());
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

    /** 报送执行核心（消费者与报送日批共用，业务幂等 + INV-5 拦截语义）。 */
    @Bean
    public BureauReportExecutionService bureauReportExecutionService(
            BureauReportAppService reportAppService,
            BureauReportRepository reportRepository,
            BureauTriggerPort triggerPort,
            SettlementConsentAnchorPort consentAnchorPort) {
        return new BureauReportExecutionService(reportAppService, reportRepository,
                triggerPort, consentAnchorPort);
    }

    /** 人工报送通道（ADR-RF-005 §5：人工报送 + 结果录入 + source=MANUAL）。 */
    @Bean
    public ManualReportAppService manualReportAppService(
            BureauReportRepository reportRepository,
            BureauMessageLogRepository messageLogRepository,
            CreditBureauReportingPolicy reportingPolicy,
            ConsentGateGuard consentGateGuard) {
        return new ManualReportAppService(reportRepository, messageLogRepository,
                reportingPolicy, consentGateGuard);
    }

    /** 报送日批（默认关：retail.bureau.report-schedule-enabled=false 不跑）。 */
    @Bean
    public BureauReportBatchJob bureauReportBatchJob(
            BureauTriggerPort triggerPort,
            BureauReportExecutionService executionService,
            BureauBatchRunPort batchRunPort,
            BusinessCalendar businessCalendar,
            @Value("${retail.bureau.report-schedule-enabled:false}") boolean scheduleEnabled) {
        return new BureauReportBatchJob(triggerPort, executionService, batchRunPort,
                businessCalendar, scheduleEnabled);
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
