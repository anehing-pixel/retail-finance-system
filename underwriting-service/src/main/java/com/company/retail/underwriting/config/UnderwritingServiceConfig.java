package com.company.retail.underwriting.config;

import com.company.retail.common.contract.ConsentGateFacade;
import com.company.retail.common.outbound.OutboundHttpClientFactory;
import com.company.retail.common.outbound.OutboundProfile;
import com.company.retail.common.outbound.OutboundProperties;
import com.company.retail.underwriting.application.AffordabilityAssessmentRepository;
import com.company.retail.underwriting.application.ConsentGateGuard;
import com.company.retail.underwriting.application.DmsResultNotificationConsumer;
import com.company.retail.underwriting.application.InquiryCompletedConsumer;
import com.company.retail.underwriting.application.OutboundTaskRetryService;
import com.company.retail.underwriting.application.UnderwritingCaseAppService;
import com.company.retail.underwriting.application.UnderwritingCaseRepository;
import com.company.retail.underwriting.application.UnderwritingGateProvider;
import com.company.retail.underwriting.domain.AffordabilityPolicy;
import com.company.retail.underwriting.domain.StubAffordabilityPolicy;
import com.company.retail.underwriting.integration.DmsResultSender;
import com.company.retail.underwriting.integration.HttpDmsOutboundAdapter;
import com.company.retail.underwriting.mapper.AffordabilityAssessmentMapper;
import com.company.retail.underwriting.mapper.OutboundTaskMapper;
import com.company.retail.underwriting.mapper.UnderwritingCaseMapper;
import com.company.retail.underwriting.schedule.DmsOutboundTaskScheduler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 审查域精确装配（R02 §3.1 范式 1）：只按「类」装配本服务 bean，
 * 共享四层模块的 bean（outbox/鉴权）由 Application 的 @Import 提供。
 */
@Configuration
@MapperScan("com.company.retail.underwriting.mapper")
public class UnderwritingServiceConfig {

    /**
     * 审查案件仓储（MyBatis 实现）。
     *
     * <p>实现类与端口同名（去 Jdbc 前缀），故此处以全限定名引用实现类，避免同编译单元同名冲突。</p>
     */
    @Bean
    public UnderwritingCaseRepository underwritingCaseRepository(UnderwritingCaseMapper mapper) {
        return new com.company.retail.underwriting.infrastructure.UnderwritingCaseRepository(mapper);
    }

    /** 支付能力评估快照仓储（MyBatis 实现）。 */
    @Bean
    public AffordabilityAssessmentRepository affordabilityAssessmentRepository(
            AffordabilityAssessmentMapper mapper) {
        return new com.company.retail.underwriting.infrastructure.AffordabilityAssessmentRepository(mapper);
    }

    /** 策略点开关（C-03 挡板）：retail.underwriting.affordability.enabled，默认开。 */
    @Bean
    public AffordabilityPolicy affordabilityPolicy(
            @Value("${retail.underwriting.affordability.enabled:true}") boolean enabled) {
        return new StubAffordabilityPolicy(enabled);
    }

    @Bean
    public ConsentGateGuard consentGateGuard(ConsentGateFacade consentGateFacade) {
        return new ConsentGateGuard(consentGateFacade);
    }

    @Bean
    public UnderwritingCaseAppService underwritingCaseAppService(
            UnderwritingCaseRepository caseRepository,
            AffordabilityAssessmentRepository assessmentRepository,
            ConsentGateGuard consentGateGuard,
            com.company.retail.common.contract.OutboxPublisher outboxPublisher,
            AffordabilityPolicy affordabilityPolicy) {
        return new UnderwritingCaseAppService(
                caseRepository, assessmentRepository, consentGateGuard, outboxPublisher, affordabilityPolicy);
    }

    @Bean
    public UnderwritingGateProvider underwritingGateProvider(UnderwritingCaseRepository caseRepository) {
        return new UnderwritingGateProvider(caseRepository);
    }

    /** 事件消费方（BC-4 → BC-3）：RfOutboxConfig 的调度器按 ObjectProvider 收集。 */
    @Bean
    public InquiryCompletedConsumer inquiryCompletedConsumer(UnderwritingCaseRepository caseRepository) {
        return new InquiryCompletedConsumer(caseRepository);
    }

    /**
     * DMS 结果通知消费方（I-02，W-SIM2）：双模——outbound-url 空=挡板 no-op 消费者，
     * 非空=真 HTTP 推送，失败落 outbound_tasks 人工补发。
     */
    @Bean
    public DmsResultSender dmsResultSender(
            @Value("${retail.dms.outbound-url:}") String outboundUrl,
            OutboundHttpClientFactory outbound) {
        return (outboundUrl == null || outboundUrl.isBlank())
                ? null
                : new HttpDmsOutboundAdapter(outboundUrl,
                        outbound.client("dms-result", OutboundProfile.ADVISORY));
    }

    @Bean
    public DmsResultNotificationConsumer dmsResultNotificationConsumer(
            ObjectProvider<DmsResultSender> senderProvider,
            OutboundTaskMapper outboundTaskMapper) {
        return new DmsResultNotificationConsumer(senderProvider.getIfAvailable(), outboundTaskMapper);
    }

    @Bean
    public OutboundTaskRetryService outboundTaskRetryService(OutboundTaskMapper outboundTaskMapper) {
        return new OutboundTaskRetryService(outboundTaskMapper);
    }

    /**
     * outbound_tasks 重试调度器：DMS 挡板模式（sender 不存在）或开关关闭时不启动。
     * 独立单线程执行器，不占用 outbox 的 {@code @Scheduled} 单线程。
     */
    @Bean(destroyMethod = "close")
    public DmsOutboundTaskScheduler dmsOutboundTaskScheduler(
            ObjectProvider<DmsResultSender> senderProvider,
            OutboundTaskMapper outboundTaskMapper,
            OutboundProperties outboundProperties) {
        DmsResultSender sender = senderProvider.getIfAvailable();
        if (sender == null || !outboundProperties.getTasks().isEnabled()) {
            return null;
        }
        DmsOutboundTaskScheduler scheduler =
                new DmsOutboundTaskScheduler(outboundTaskMapper, sender, outboundProperties.getTasks());
        scheduler.start();
        return scheduler;
    }
}
