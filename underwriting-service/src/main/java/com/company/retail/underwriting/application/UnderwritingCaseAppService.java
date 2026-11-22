package com.company.retail.underwriting.application;

import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.underwriting.domain.AffordabilityInput;
import com.company.retail.underwriting.domain.AssessmentOutput;
import com.company.retail.underwriting.domain.DecisionOutcome;
import com.company.retail.underwriting.domain.UnderwritingCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审查用例服务（一个用例一个本地事务；业务写 + outbox 事件写同一事务，R02 §3 / P-4）。
 *
 * <p>闸门语义（R05 §4）：G3 同意门禁在案件建立与决定时<b>各查一次</b>
 * （fail-closed、禁止缓存——同意可秒级撤回，X-3）；G6 决定留痕 + SOD 在聚合内强制。</p>
 */
public class UnderwritingCaseAppService {

    private static final Logger log = LoggerFactory.getLogger(UnderwritingCaseAppService.class);

    private final UnderwritingCaseRepository caseRepository;
    private final AffordabilityAssessmentRepository assessmentRepository;
    private final ConsentGateGuard consentGateGuard;
    private final OutboxPublisher outboxPublisher;
    /** 策略点挂钩：由配置装配（W4 用 StubAffordabilityPolicy；C-03 公式落地时换实现）。 */
    private final com.company.retail.underwriting.domain.AffordabilityPolicy affordabilityPolicy;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UnderwritingCaseAppService(UnderwritingCaseRepository caseRepository,
                                      AffordabilityAssessmentRepository assessmentRepository,
                                      ConsentGateGuard consentGateGuard,
                                      OutboxPublisher outboxPublisher,
                                      com.company.retail.underwriting.domain.AffordabilityPolicy affordabilityPolicy) {
        this.caseRepository = caseRepository;
        this.assessmentRepository = assessmentRepository;
        this.consentGateGuard = consentGateGuard;
        this.outboxPublisher = outboxPublisher;
        this.affordabilityPolicy = affordabilityPolicy;
    }

    /** 建立审查案件：G3 同意门禁 fail-closed → 落 PENDING 案件。 */
    @Transactional
    public UnderwritingCase createCase(long applicationId, long consentId, String ruleVersion, String traceId) {
        // G3：同意缺失/已撤回/门禁不可用 → RF_UW_003，硬阻断（不可配置旁路）
        consentGateGuard.requireConsentValid(consentId);
        UnderwritingCase testCase = UnderwritingCase.start(applicationId, consentId, ruleVersion);
        caseRepository.insert(testCase);
        log.info("审查案件建立 caseId={} applicationId={} traceId={}",
                testCase.getCaseId(), applicationId, traceId);
        return testCase;
    }

    /** 发起征信查询（状态推进）。 */
    @Transactional
    public UnderwritingCase startInquiry(long caseId) {
        UnderwritingCase testCase = loadCase(caseId);
        testCase.startInquiry();
        caseRepository.update(testCase);
        return testCase;
    }

    /**
     * 支付能力评估（G5 / C-03 挡板）：INQUIRY_DONE → ASSESSING，评估快照 append-only。
     * FAIL 不抛异常到调用方之外——快照记 FAIL，由决定环节人工判断（R05 G5：可转 REFERRED）。
     */
    @Transactional
    public AssessmentOutput assessAffordability(long caseId, AffordabilityInput input, String traceId) {
        UnderwritingCase testCase = loadCase(caseId);
        assessmentRepository.findLatest(caseId).ifPresent(existing -> {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_CONFLICT_001,
                    Map.of("reason", "ASSESSMENT_EXISTS", "caseId", String.valueOf(caseId)));
        });
        testCase.beginAssessment();
        AssessmentOutput output = affordabilityPolicy.assess(input);
        assessmentRepository.insert(caseId, input, output, traceId);
        caseRepository.update(testCase);
        log.info("支付能力评估完成 caseId={} result={} reason={} traceId={}",
                caseId, output.result(), output.reasonCode(), traceId);
        return output;
    }

    /**
     * 审查决定（G6）：决定留痕 + 状态机 + 事件（同一事务）。
     * 决定前复核同意有效性（X-3：同意在审查中被撤回 → 中止决定）。
     *
     * @return 决定后的案件
     */
    @Transactional
    public UnderwritingCase decide(long caseId, String decidedBy, DecisionOutcome outcome,
                                   String reasonCode, String traceId) {
        UnderwritingCase testCase = loadCase(caseId);
        consentGateGuard.requireConsentValid(testCase.getConsentId());
        DecisionOutcome recorded = testCase.decide(decidedBy, outcome, testCase.getRuleVersion());
        caseRepository.update(testCase);
        publishDecisionEvent(testCase, recorded, reasonCode, traceId);
        return testCase;
    }

    /**
     * 人工复核 / override（G6 + I-6）：复核人 ≠ 决定人由聚合 SOD 硬阻断（RF_AUTH_003）。
     */
    @Transactional
    public UnderwritingCase overrideDecision(long caseId, String reviewedBy, DecisionOutcome outcome,
                                             String reasonCode, String traceId) {
        UnderwritingCase testCase = loadCase(caseId);
        consentGateGuard.requireConsentValid(testCase.getConsentId());
        testCase.overrideDecision(reviewedBy, outcome);
        caseRepository.update(testCase);
        publishDecisionEvent(testCase, outcome, reasonCode, traceId);
        return testCase;
    }

    /** 承認有効期已过：APPROVED → EXPIRED（终态）。 */
    @Transactional
    public UnderwritingCase expire(long caseId) {
        UnderwritingCase testCase = loadCase(caseId);
        testCase.expire();
        caseRepository.update(testCase);
        return testCase;
    }

    /** 事件 payload 只装白名单键（R09 §5.2）；发布前守卫在 OutboxPublisher 内强制（INV-4）。 */
    private void publishDecisionEvent(UnderwritingCase testCase, DecisionOutcome outcome,
                                      String reasonCode, String traceId) {
        // R04 §6：只有 APPROVED / DECLINED 有事件；REFERRED 是人工复核中间态，不发事件
        if (outcome == DecisionOutcome.REFERRED) {
            return;
        }
        String eventType = outcome == DecisionOutcome.APPROVED
                ? OutboxPublisher.UNDERWRITING_APPROVED : OutboxPublisher.UNDERWRITING_DECLINED;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", testCase.getCaseId());
        payload.put("applicationId", testCase.getApplicationId());
        payload.put("status", testCase.getStatus().name());
        payload.put("outcomeCode", outcome.name());
        if (reasonCode != null && !reasonCode.isBlank()) {
            payload.put("reasonCode", reasonCode);
        }
        if (testCase.getRuleVersion() != null) {
            payload.put("ruleVersion", testCase.getRuleVersion());
        }
        payload.put("occurredAt", java.time.OffsetDateTime.now().toString());
        payload.put("schemaVersion", "1.0");
        payload.put("traceId", traceId);
        try {
            String json = objectMapper.writeValueAsString(payload);
            outboxPublisher.publish(eventType, String.valueOf(testCase.getCaseId()), json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("审查决定事件 payload 序列化失败", e);
        }
    }

    private UnderwritingCase loadCase(long caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new com.company.platform.exception.BusinessException(
                        com.company.retail.domain.exception.RFErrorCode.RF_VALID_001,
                        Map.of("reason", "CASE_NOT_FOUND", "caseId", String.valueOf(caseId))));
    }
}
