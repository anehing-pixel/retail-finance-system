package com.company.retail.bureau.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.bureau.acl.BureauAdapter;
import com.company.retail.bureau.acl.BureauReportCommand;
import com.company.retail.bureau.acl.ReportOutcome;
import com.company.retail.bureau.domain.CreditBureau;
import com.company.retail.bureau.domain.CreditBureauReportingPolicy;
import com.company.retail.bureau.domain.ReportStatus;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.domain.exception.RFErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 征信报送用例（BC-4，ADR-RF-005 链路二「登记/更新」，C-05：报送主 CIC、T+1）。
 *
 * <ul>
 *   <li><b>INV-5 / RB-1</b>：同意缺失/撤回 → 不报送（RF_UW_003，fail-closed）；</li>
 *   <li>幂等：uk_report_loan_trigger（loan_id, trigger）唯一，重复 → RF_CONFLICT_001；</li>
 *   <li>成功发 {@code CREDIT_BUREAU_REPORT_SENT}（聚合键 reportId，payload 过白名单守卫）；</li>
 *   <li>失败 X-11：保留失败报文 + REJECTED 状态，人工重触发，不静默丢弃；</li>
 *   <li>策略点关闭 → PENDING + 人工通道留痕（不发事件、不静默丢弃）。</li>
 * </ul>
 */
public class BureauReportAppService {

    private static final Logger log = LoggerFactory.getLogger(BureauReportAppService.class);

    private final BureauReportRepository reportRepository;
    private final BureauMessageLogRepository messageLogRepository;
    private final Map<CreditBureau, BureauAdapter> adapters;
    private final CreditBureauReportingPolicy reportingPolicy;
    private final ConsentGateGuard consentGateGuard;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BureauReportAppService(BureauReportRepository reportRepository,
                                  BureauMessageLogRepository messageLogRepository,
                                  Map<CreditBureau, BureauAdapter> adapters,
                                  CreditBureauReportingPolicy reportingPolicy,
                                  ConsentGateGuard consentGateGuard,
                                  OutboxPublisher outboxPublisher) {
        this.reportRepository = reportRepository;
        this.messageLogRepository = messageLogRepository;
        this.adapters = adapters;
        this.reportingPolicy = reportingPolicy;
        this.consentGateGuard = consentGateGuard;
        this.outboxPublisher = outboxPublisher;
    }

    /** 发起报送（T+1 业务日期由策略点给出；主 CIC）。 */
    @Transactional
    public ReportRow sendReport(long loanId, long consentId, String trigger, String traceId) {
        if (trigger == null || trigger.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", "trigger"));
        }
        // INV-5：报送同样必须挂在有效同意上（consent_id NOT NULL + 门禁 fail-closed）
        consentGateGuard.requireConsentValid(consentId);

        CreditBureau bureau = reportingPolicy.primaryReportBureau();
        if (!reportRepository.insert(loanId, consentId, bureau.name(), trigger, traceId)) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001,
                    Map.of("reason", "REPORT_DUPLICATE", "loanId", String.valueOf(loanId)));
        }
        // 插入后取回 reportId（挡板/真实接入同构：仓储按 (loan_id, trigger) 定位）
        long reportId = reportRepository.findByLoanAndTrigger(loanId, trigger)
                .orElseThrow(() -> new IllegalStateException("报送留痕插入后未能回读 reportId"))
                .reportId();

        // 策略点开关：关闭 → 挂 PENDING 走人工报送通道（留痕不丢弃、不发事件）
        if (!reportingPolicy.enabled()) {
            reportRepository.markFinished(reportId, ReportStatus.PENDING.name(),
                    "MANUAL_PENDING", null, traceId);
            messageLogRepository.append("OUTBOUND", bureau.name(), String.valueOf(reportId),
                    "{\"type\":\"REPORT\",\"manual\":true,\"loanId\":" + loanId + "}", traceId);
            log.info("报送转人工通道 reportId={} loanId={}（策略点关闭）", reportId, loanId);
            return toRow(reportRepository.findById(reportId).orElseThrow());
        }

        return doSend(reportId, loanId, consentId, bureau, trigger, traceId);
    }

    /** 人工重触发（X-11 / R09：POST /api/bureau-reports 手动重送，BUREAU_REPORT_EXEC）。 */
    @Transactional
    public ReportRow resend(long reportId, String traceId) {
        BureauReportRepository.ReportRow row = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        Map.of("reason", "REPORT_NOT_FOUND", "reportId", String.valueOf(reportId))));
        if (ReportStatus.SENT.name().equals(row.status())) {
            throw new BusinessException(RFErrorCode.RF_VALID_002, Map.of("reason", "ALREADY_SENT"));
        }
        return doSend(reportId, row.loanId(), row.consentId(), CreditBureau.valueOf(row.bureau()),
                row.trigger(), traceId);
    }

    private ReportRow doSend(long reportId, long loanId, long consentId, CreditBureau bureau,
                             String trigger, String traceId) {
        String bizDate = reportingPolicy.nextReportBizDate(LocalDate.now()).toString();
        ReportOutcome outcome;
        try {
            messageLogRepository.append("OUTBOUND", bureau.name(), String.valueOf(reportId),
                    "{\"type\":\"REPORT\",\"loanId\":" + loanId + ",\"trigger\":\"" + trigger
                            + "\",\"bizDate\":\"" + bizDate + "\"}", traceId);
            outcome = adapters.get(bureau).submitReport(
                    new BureauReportCommand(loanId, consentId, bureau, trigger, bizDate));
        } catch (Exception e) {
            // X-11：失败保留报文留痕 + REJECTED，人工重触发；不静默丢弃
            reportRepository.markFinished(reportId, ReportStatus.REJECTED.name(), null, null, traceId);
            messageLogRepository.append("INBOUND", bureau.name(), String.valueOf(reportId),
                    "{\"type\":\"REPORT\",\"error\":\"" + e.getClass().getSimpleName() + "\"}", traceId);
            throw new BusinessException(RFErrorCode.RF_BUREAU_001,
                    Map.of("reportId", String.valueOf(reportId), "reason", "ADAPTER_ERROR"));
        }
        messageLogRepository.append("INBOUND", bureau.name(), String.valueOf(reportId),
                outcome.rawMessage(), traceId);
        boolean sent = "SENT".equals(outcome.status());
        reportRepository.markFinished(reportId,
                sent ? ReportStatus.SENT.name() : ReportStatus.REJECTED.name(),
                bizDate, outcome.responseRef(), traceId);
        if (!sent) {
            throw new BusinessException(RFErrorCode.RF_BUREAU_001,
                    Map.of("reportId", String.valueOf(reportId), "reason", "BUREAU_REJECTED"));
        }
        publishSent(reportId, traceId);
        log.info("征信报送成功 reportId={} loanId={} bureau={} traceId={}", reportId, loanId, bureau, traceId);
        return toRow(reportRepository.findById(reportId).orElseThrow());
    }

    private void publishSent(long reportId, String traceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", reportId);
        payload.put("status", ReportStatus.SENT.name());
        payload.put("source", "AUTO");
        payload.put("occurredAt", java.time.OffsetDateTime.now().toString());
        payload.put("schemaVersion", "1.0");
        payload.put("traceId", traceId);
        try {
            outboxPublisher.publish(OutboxPublisher.CREDIT_BUREAU_REPORT_SENT,
                    String.valueOf(reportId), objectMapper.writeValueAsString(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("征信报送事件 payload 序列化失败", e);
        }
    }

    private static ReportRow toRow(BureauReportRepository.ReportRow row) {
        return new ReportRow(row.reportId(), row.loanId(), row.bureau(), row.trigger(), row.status());
    }

    /** 视图行（web 层用；不含 PII）。 */
    public record ReportRow(long reportId, long loanId, String bureau, String trigger, String status) {
    }
}
