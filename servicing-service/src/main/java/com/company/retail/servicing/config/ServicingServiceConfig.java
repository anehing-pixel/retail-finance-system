package com.company.retail.servicing.config;

import com.company.platform.calendar.BusinessCalendar;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.common.contract.ServicingConsentGate;
import com.company.retail.servicing.application.AuditWriter;
import com.company.retail.servicing.application.BatchRunRepository;
import com.company.retail.servicing.application.BureauReportTriggerAppService;
import com.company.retail.servicing.application.BureauReportTriggerRepository;
import com.company.retail.servicing.application.CollectionActionRepository;
import com.company.retail.servicing.application.CollectionCaseAppService;
import com.company.retail.servicing.application.CollectionCaseRepository;
import com.company.retail.servicing.application.ConsentValidityGate;
import com.company.retail.servicing.application.DpdBatchAppService;
import com.company.retail.servicing.application.DpdSnapshotRepository;
import com.company.retail.servicing.application.LoanScheduleQueryPort;
import com.company.retail.servicing.application.OutboxTrackingPort;
import com.company.retail.servicing.domain.CollectionTimePolicy;
import com.company.retail.servicing.domain.DpdCalculator;
import com.company.retail.servicing.infrastructure.JdbcAuditWriter;
import com.company.retail.servicing.infrastructure.JdbcBatchRunRepository;
import com.company.retail.servicing.infrastructure.JdbcBureauReportTriggerRepository;
import com.company.retail.servicing.infrastructure.JdbcCollectionCaseRepository;
import com.company.retail.servicing.infrastructure.JdbcDpdSnapshotRepository;
import com.company.retail.servicing.infrastructure.JdbcLoanScheduleQueryPort;
import com.company.retail.servicing.infrastructure.JdbcOutboxTrackingPort;
import com.company.retail.infrastructure.persistence.JdbcBusinessCalendar;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalTime;

/**
 * 贷后域精确装配（R02 §3.1 范式 1）：DPD / 催收 / 报送触发 + 营业日历 + 审计落表。
 */
@Configuration
public class ServicingServiceConfig {

    @Bean
    public LoanScheduleQueryPort loanScheduleQueryPort(JdbcTemplate jdbcTemplate) {
        return new JdbcLoanScheduleQueryPort(jdbcTemplate);
    }

    @Bean
    public DpdSnapshotRepository dpdSnapshotRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcDpdSnapshotRepository(jdbcTemplate);
    }

    @Bean
    public CollectionCaseRepository collectionCaseRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcCollectionCaseRepository(jdbcTemplate);
    }

    @Bean
    public CollectionActionRepository collectionActionRepository(
            JdbcCollectionCaseRepository collectionCaseRepository) {
        return collectionCaseRepository.actionRepository();
    }

    @Bean
    public BureauReportTriggerRepository bureauReportTriggerRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcBureauReportTriggerRepository(jdbcTemplate);
    }

    @Bean
    public BatchRunRepository batchRunRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcBatchRunRepository(jdbcTemplate);
    }

    @Bean
    public OutboxTrackingPort outboxTrackingPort(JdbcTemplate jdbcTemplate) {
        return new JdbcOutboxTrackingPort(jdbcTemplate);
    }

    @Bean
    public AuditWriter auditWriter(JdbcTemplate jdbcTemplate) {
        return new JdbcAuditWriter(jdbcTemplate);
    }

    /** 营业日历（共享 infrastructure 机制，core.holidays；T+1 报送口径用）。 */
    @Bean
    public BusinessCalendar businessCalendar(JdbcTemplate jdbcTemplate) {
        return new JdbcBusinessCalendar(jdbcTemplate);
    }

    /** 同意门禁（INV-5 fail-closed；consumer 侧 Facade 与 W4 同构）。 */
    @Bean
    public ConsentValidityGate consentValidityGate(ServicingConsentGate servicingConsentGate) {
        return servicingConsentGate;
    }

    /** DPD 计算器（宽限期 ⚙️ 默认 0，配置覆盖 P-12）。 */
    @Bean
    public DpdCalculator dpdCalculator(
            @Value("${retail.servicing.dpd-grace-days:0}") int graceDays) {
        return new DpdCalculator(graceDays);
    }

    /** 催收时段窗口（⚙️ 默认 09:00–20:00 JST；窗口可配，约束本身不可关闭）。 */
    @Bean
    public CollectionTimePolicy collectionTimePolicy(
            @Value("${retail.servicing.collection-window-start:09:00}") String start,
            @Value("${retail.servicing.collection-window-end:20:00}") String end) {
        return new CollectionTimePolicy(LocalTime.parse(start), LocalTime.parse(end));
    }

    @Bean
    public DpdBatchAppService dpdBatchAppService(LoanScheduleQueryPort loanScheduleQueryPort,
                                                 DpdSnapshotRepository snapshotRepository,
                                                 CollectionCaseRepository caseRepository,
                                                 BatchRunRepository batchRunRepository,
                                                 AuditWriter auditWriter,
                                                 OutboxPublisher outboxPublisher,
                                                 DpdCalculator calculator) {
        return new DpdBatchAppService(loanScheduleQueryPort, snapshotRepository, caseRepository,
                batchRunRepository, auditWriter, outboxPublisher, calculator);
    }

    @Bean
    public CollectionCaseAppService collectionCaseAppService(
            CollectionCaseRepository caseRepository,
            CollectionActionRepository actionRepository,
            CollectionTimePolicy timePolicy,
            AuditWriter auditWriter) {
        return new CollectionCaseAppService(caseRepository, actionRepository, timePolicy, auditWriter);
    }

    @Bean
    public BureauReportTriggerAppService bureauReportTriggerAppService(
            BureauReportTriggerRepository triggerRepository,
            ConsentValidityGate consentValidityGate,
            OutboxTrackingPort outboxTrackingPort,
            BusinessCalendar businessCalendar,
            AuditWriter auditWriter,
            @Value("${retail.servicing.report-lead-days:1}") int reportLeadDays) {
        return new BureauReportTriggerAppService(triggerRepository, consentValidityGate,
                outboxTrackingPort, businessCalendar, auditWriter, reportLeadDays);
    }
}
