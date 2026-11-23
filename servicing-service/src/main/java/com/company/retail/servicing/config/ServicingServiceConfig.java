package com.company.retail.servicing.config;

import com.company.platform.calendar.BusinessCalendar;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.common.contract.ServicingConsentGate;
import com.company.retail.infrastructure.mapper.BusinessCalendarMapper;
import com.company.retail.infrastructure.persistence.MyBatisBusinessCalendar;
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
import com.company.retail.servicing.infrastructure.MyBatisAuditWriter;
import com.company.retail.servicing.infrastructure.MyBatisBatchRunRepository;
import com.company.retail.servicing.infrastructure.MyBatisBureauReportTriggerRepository;
import com.company.retail.servicing.infrastructure.MyBatisCollectionCaseRepository;
import com.company.retail.servicing.infrastructure.MyBatisDpdSnapshotRepository;
import com.company.retail.servicing.infrastructure.MyBatisLoanScheduleQueryPort;
import com.company.retail.servicing.infrastructure.MyBatisOutboxTrackingPort;
import com.company.retail.servicing.mapper.AuditMapper;
import com.company.retail.servicing.mapper.BatchRunMapper;
import com.company.retail.servicing.mapper.BureauReportTriggerMapper;
import com.company.retail.servicing.mapper.CollectionActionMapper;
import com.company.retail.servicing.mapper.CollectionCaseMapper;
import com.company.retail.servicing.mapper.DpdSnapshotMapper;
import com.company.retail.servicing.mapper.LoanScheduleQueryMapper;
import com.company.retail.servicing.mapper.OutboxTrackingMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalTime;

/**
 * 贷后域精确装配（R02 §3.1 范式 1）：DPD / 催收 / 报送触发 + 营业日历 + 审计落表。
 *
 * <p>持久层统一走 MyBatis 注解 SQL（{@code ServicingMyBatisConfig} 显式注册本服务 Mapper 包）；
 * 本类只做「端口 → 实现」的按类注入，不做组件全包扫描。</p>
 */
@Configuration
public class ServicingServiceConfig {

    @Bean
    public LoanScheduleQueryPort loanScheduleQueryPort(LoanScheduleQueryMapper mapper) {
        return new MyBatisLoanScheduleQueryPort(mapper);
    }

    @Bean
    public DpdSnapshotRepository dpdSnapshotRepository(DpdSnapshotMapper mapper) {
        return new MyBatisDpdSnapshotRepository(mapper);
    }

    @Bean
    public CollectionCaseRepository collectionCaseRepository(CollectionCaseMapper mapper,
                                                           CollectionActionMapper actionMapper) {
        return new MyBatisCollectionCaseRepository(mapper, actionMapper);
    }

    @Bean
    public CollectionActionRepository collectionActionRepository(
            MyBatisCollectionCaseRepository collectionCaseRepository) {
        return collectionCaseRepository.actionRepository();
    }

    @Bean
    public BureauReportTriggerRepository bureauReportTriggerRepository(BureauReportTriggerMapper mapper) {
        return new MyBatisBureauReportTriggerRepository(mapper);
    }

    @Bean
    public BatchRunRepository batchRunRepository(BatchRunMapper mapper) {
        return new MyBatisBatchRunRepository(mapper);
    }

    @Bean
    public OutboxTrackingPort outboxTrackingPort(OutboxTrackingMapper mapper) {
        return new MyBatisOutboxTrackingPort(mapper);
    }

    @Bean
    public AuditWriter auditWriter(AuditMapper mapper) {
        return new MyBatisAuditWriter(mapper);
    }

    /** 营业日历（共享 infrastructure 机制，core.holidays；T+1 报送口径用）。 */
    @Bean
    public BusinessCalendar businessCalendar(BusinessCalendarMapper mapper) {
        return new MyBatisBusinessCalendar(mapper);
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
