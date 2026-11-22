package com.company.retail.underwriting.config;

import com.company.retail.common.contract.ConsentGateFacade;
import com.company.retail.underwriting.application.AffordabilityAssessmentRepository;
import com.company.retail.underwriting.application.ConsentGateGuard;
import com.company.retail.underwriting.application.InquiryCompletedConsumer;
import com.company.retail.underwriting.application.UnderwritingCaseAppService;
import com.company.retail.underwriting.application.UnderwritingCaseRepository;
import com.company.retail.underwriting.application.UnderwritingGateProvider;
import com.company.retail.underwriting.domain.AffordabilityPolicy;
import com.company.retail.underwriting.domain.StubAffordabilityPolicy;
import com.company.retail.underwriting.infrastructure.JdbcAffordabilityAssessmentRepository;
import com.company.retail.underwriting.infrastructure.JdbcUnderwritingCaseRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 审查域精确装配（R02 §3.1 范式 1）：只按「类」装配本服务 bean，
 * 共享四层模块的 bean（outbox/鉴权）由 Application 的 @Import 提供。
 */
@Configuration
public class UnderwritingServiceConfig {

    @Bean
    public UnderwritingCaseRepository underwritingCaseRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcUnderwritingCaseRepository(jdbcTemplate);
    }

    @Bean
    public AffordabilityAssessmentRepository affordabilityAssessmentRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAffordabilityAssessmentRepository(jdbcTemplate);
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
}
