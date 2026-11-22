package com.company.retail.bureau.application;

import com.company.retail.bureau.acl.BureauAdapter;
import com.company.retail.bureau.acl.BureauInquiryCommand;
import com.company.retail.bureau.acl.InquiryOutcome;
import com.company.retail.bureau.domain.CreditBureau;
import com.company.retail.bureau.domain.CreditBureauReportingPolicy;
import com.company.retail.bureau.domain.InquiryStatus;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.platform.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 征信查询用例（BC-4，R05 G4 / ADR-RF-005 链路一「查询」）。
 *
 * <p>不变式与红线：</p>
 * <ul>
 *   <li><b>INV-5 / RB-1</b>：同意缺失/已撤回/门禁不可用 → 不发起查询（RF_UW_003，fail-closed）；</li>
 *   <li><b>G4 / X-2</b>：查询失败不写空结果、不留假 COMPLETED；同一案件同一机构失败
 *       达 3 次 → RF_UW_001 转人工（人工查询 + 结果录入 source=MANUAL 是正式通道）；</li>
 *   <li>机构编码只存在于 ACL；结果落快照引用 append-only；</li>
 *   <li>查询完成发 {@code CREDIT_INQUIRY_COMPLETED}（聚合键 inquiryId，payload 白名单）。</li>
 * </ul>
 */
public class CreditInquiryAppService {

    private static final Logger log = LoggerFactory.getLogger(CreditInquiryAppService.class);
    private static final int MAX_AUTO_RETRY = 3;

    private final CreditInquiryRepository inquiryRepository;
    private final BureauMessageLogRepository messageLogRepository;
    private final Map<CreditBureau, BureauAdapter> adapters;
    private final CreditBureauReportingPolicy reportingPolicy;
    private final ConsentGateGuard consentGateGuard;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CreditInquiryAppService(CreditInquiryRepository inquiryRepository,
                                   BureauMessageLogRepository messageLogRepository,
                                   Map<CreditBureau, BureauAdapter> adapters,
                                   CreditBureauReportingPolicy reportingPolicy,
                                   ConsentGateGuard consentGateGuard,
                                   OutboxPublisher outboxPublisher) {
        this.inquiryRepository = inquiryRepository;
        this.messageLogRepository = messageLogRepository;
        this.adapters = adapters;
        this.reportingPolicy = reportingPolicy;
        this.consentGateGuard = consentGateGuard;
        this.outboxPublisher = outboxPublisher;
    }

    @Transactional
    public InquiryRow execute(long caseId, long consentId, String bureauName, String purpose, String traceId) {
        // 策略点开关（C-05）：关闭即拒绝自动查询，转人工通道
        if (!reportingPolicy.enabled()) {
            throw new BusinessException(RFErrorCode.RF_VALID_002,
                    Map.of("reason", "BUREAU_POLICY_DISABLED", "hint", "走人工查询 + 结果录入 source=MANUAL"));
        }
        CreditBureau bureau = parseBureau(bureauName);
        if (!reportingPolicy.inquiryTargets().contains(bureau)) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of(
                    "reason", "BUREAU_NOT_IN_COMBINATION",
                    "bureau", bureau.name(),
                    "combination", reportingPolicy.inquiryTargets().toString()));
        }
        if (purpose == null || purpose.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", "purpose"));
        }

        // G4 前置：INV-5 同意硬前置（fail-closed，禁止缓存门禁结果）
        consentGateGuard.requireConsentValid(consentId);

        // 重试上限：同一案件同一机构失败 3 次 → 转人工（不自动无限重试）
        if (inquiryRepository.countFailed(caseId, bureau.name()) >= MAX_AUTO_RETRY) {
            throw new BusinessException(RFErrorCode.RF_UW_001, Map.of(
                    "reason", "RETRY_LIMIT_REACHED", "hint", "转人工查询"));
        }

        long inquiryId = inquiryRepository.insert(caseId, consentId, bureau.name(), purpose, traceId);
        BureauAdapter adapter = adapters.get(bureau);
        InquiryOutcome outcome;
        try {
            // 报文留痕：出站指令（挡板为合成指令；真实接入为专线报文）
            messageLogRepository.append("OUTBOUND", bureau.name(), String.valueOf(inquiryId),
                    "{\"type\":\"INQUIRY\",\"caseId\":" + caseId + ",\"purpose\":\"" + purpose + "\"}", traceId);
            outcome = adapter.inquire(new BureauInquiryCommand(caseId, consentId, bureau, purpose));
        } catch (Exception e) {
            // 机构故障语义被 ACL 翻译：失败也留痕（FAILED），不写空结果
            inquiryRepository.markFinished(inquiryId, InquiryStatus.FAILED, null, 0, traceId);
            messageLogRepository.append("INBOUND", bureau.name(), String.valueOf(inquiryId),
                    "{\"type\":\"INQUIRY\",\"error\":\"" + e.getClass().getSimpleName() + "\"}", traceId);
            throw new BusinessException(RFErrorCode.RF_UW_001,
                    Map.of("inquiryId", String.valueOf(inquiryId), "reason", "ADAPTER_ERROR"));
        }
        messageLogRepository.append("INBOUND", bureau.name(), String.valueOf(inquiryId),
                outcome.rawMessage(), traceId);
        inquiryRepository.markFinished(inquiryId,
                "COMPLETED".equals(outcome.status()) ? InquiryStatus.COMPLETED : InquiryStatus.FAILED,
                outcome.resultSummary(), outcome.costAmount(), traceId);

        if (!"COMPLETED".equals(outcome.status())) {
            throw new BusinessException(RFErrorCode.RF_UW_001,
                    Map.of("inquiryId", String.valueOf(inquiryId), "reason", "BUREAU_REJECTED"));
        }
        publishCompleted(inquiryId, caseId, traceId);
        log.info("征信查询完成 inquiryId={} caseId={} bureau={} traceId={}",
                inquiryId, caseId, bureau, traceId);
        return inquiryRepository.findById(inquiryId)
                .map(r -> new InquiryRow(r.inquiryId(), r.caseId(), r.bureau(), r.purpose(), r.status()))
                .orElseThrow();
    }

    /** 事件 payload 只装白名单键（R09 §5.2）；守卫在 OutboxPublisher 内强制（INV-4）。 */
    private void publishCompleted(long inquiryId, long caseId, String traceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inquiryId", inquiryId);
        payload.put("caseId", caseId);
        payload.put("status", "COMPLETED");
        payload.put("occurredAt", java.time.OffsetDateTime.now().toString());
        payload.put("schemaVersion", "1.0");
        payload.put("traceId", traceId);
        try {
            outboxPublisher.publish(OutboxPublisher.CREDIT_INQUIRY_COMPLETED,
                    String.valueOf(inquiryId), objectMapper.writeValueAsString(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("征信查询事件 payload 序列化失败", e);
        }
    }

    private static CreditBureau parseBureau(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", "bureau"));
        }
        try {
            return CreditBureau.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", "bureau", "value", name));
        }
    }

    public record InquiryRow(long inquiryId, long caseId, String bureau, String purpose, String status) {
    }
}
