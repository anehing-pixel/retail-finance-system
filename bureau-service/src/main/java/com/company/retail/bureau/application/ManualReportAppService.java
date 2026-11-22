package com.company.retail.bureau.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.bureau.application.BureauReportRepository.ReportRow;
import com.company.retail.bureau.domain.CreditBureau;
import com.company.retail.bureau.domain.CreditBureauReportingPolicy;
import com.company.retail.bureau.domain.ReportStatus;
import com.company.retail.domain.exception.RFErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 人工报送通道（ADR-RF-005 §5 接入未审批前的降级：人工报送 + 结果录入 + source=MANUAL）。
 *
 * <p>与自动通道汇入<b>同一张真相源表</b>（integration.credit_bureau_reports）与
 * <b>同一报文留痕表</b>（integration.bureau_message_logs）；挡板语义：录入端点
 * 不真调机构（BureauAdapter 不在此链路上），录入内容即机构返回报文的留痕原文。</p>
 *
 * <ul>
 *   <li>INV-5：登记同样必须锚定有效同意（fail-closed，RF_UW_003）；</li>
 *   <li>uk_report_loan_trigger：同 (loan, MANUAL) 只能有一笔在途人工报送；</li>
 *   <li>状态：登记 → PENDING；结果录入 → SENT / REJECTED（X-11：不静默丢弃）；</li>
 *   <li>人工通道<b>不发</b> CREDIT_BUREAU_REPORT_SENT 事件（事件语义 = 自动通道机器回执），
 *       留痕经审计与 message_logs 追溯（已登记最终报告说明）。</li>
 * </ul>
 */
public class ManualReportAppService {

    /** 人工通道触发值（ReportTrigger：MANUAL，BureauReportCommand javadoc 口径）。 */
    public static final String TRIGGER_MANUAL = "MANUAL";

    private static final Logger log = LoggerFactory.getLogger(ManualReportAppService.class);

    private final BureauReportRepository reportRepository;
    private final BureauMessageLogRepository messageLogRepository;
    private final CreditBureauReportingPolicy reportingPolicy;
    private final ConsentGateGuard consentGateGuard;

    public ManualReportAppService(BureauReportRepository reportRepository,
                                  BureauMessageLogRepository messageLogRepository,
                                  CreditBureauReportingPolicy reportingPolicy,
                                  ConsentGateGuard consentGateGuard) {
        this.reportRepository = reportRepository;
        this.messageLogRepository = messageLogRepository;
        this.reportingPolicy = reportingPolicy;
        this.consentGateGuard = consentGateGuard;
    }

    /** 员工登记人工报送请求（PENDING + source=MANUAL + OUTBOUND 报文留痕）。 */
    @Transactional
    public ReportRow registerManualRequest(long loanId, long consentId, String bureauOrNull,
                                           String note, String traceId) {
        if (loanId <= 0 || consentId <= 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    Map.of("reason", "LOAN_AND_CONSENT_REQUIRED"));
        }
        // INV-5 / RB-1：人工通道同样 fail-closed（查不到/撤回一律拒绝，历史留痕不删除）
        consentGateGuard.requireConsentValid(consentId);

        String bureau = bureauOrNull == null || bureauOrNull.isBlank()
                ? reportingPolicy.primaryReportBureau().name() : bureauOrNull;
        // 机构组合校验（C-05：策略点机构组合；组合外机构一律拒绝，与查询侧同口径）
        boolean inCombination = java.util.Arrays.stream(CreditBureau.values())
                .anyMatch(b -> b.name().equals(bureau))
                && reportingPolicy.inquiryTargets().contains(CreditBureau.valueOf(bureau));
        if (!inCombination) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    Map.of("reason", "BUREAU_NOT_IN_COMBINATION", "bureau", bureau));
        }
        if (!reportRepository.insertWithSource(loanId, consentId, bureau, TRIGGER_MANUAL,
                "MANUAL", traceId)) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001, Map.of(
                    "reason", "MANUAL_REPORT_DUPLICATE", "loanId", String.valueOf(loanId)));
        }
        long reportId = reportRepository.findByLoanAndTrigger(loanId, TRIGGER_MANUAL)
                .orElseThrow(() -> new IllegalStateException("人工报送登记插入后未能回读 reportId"))
                .reportId();
        messageLogRepository.append("OUTBOUND", bureau, String.valueOf(reportId),
                "{\"type\":\"MANUAL_REQUEST\",\"manual\":true,\"loanId\":" + loanId
                        + ",\"note\":" + jsonQuote(note) + "}", traceId);
        log.info("人工报送请求登记 reportId={} loanId={} bureau={} traceId={}",
                reportId, loanId, bureau, traceId);
        return reportRepository.findById(reportId).orElseThrow();
    }

    /**
     * 员工录入机构返回结果（挡板：不真调机构；录入报文落 INBOUND 留痕）。
     * outcome：ACCEPTED → SENT；其余 → REJECTED（X-11 保留录入报文，可再录入）。
     */
    @Transactional
    public ReportRow recordManualResult(long reportId, String rawMessage, String responseRef,
                                        String outcome, String traceId) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", "rawMessage"));
        }
        ReportRow row = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        Map.of("reason", "REPORT_NOT_FOUND", "reportId", String.valueOf(reportId))));
        if (!ReportStatus.PENDING.name().equals(row.status())) {
            throw new BusinessException(RFErrorCode.RF_VALID_002,
                    Map.of("reason", "RESULT_ALREADY_RECORDED", "status", row.status()));
        }
        boolean accepted = "ACCEPTED".equalsIgnoreCase(outcome);
        // 录入的报文原文留痕（PII-4：只在留痕表出现，不进事件/应用日志）
        messageLogRepository.append("INBOUND", row.bureau(), String.valueOf(reportId), rawMessage, traceId);
        reportRepository.markFinished(reportId,
                accepted ? ReportStatus.SENT.name() : ReportStatus.REJECTED.name(),
                null, responseRef, traceId);
        log.info("人工报送结果录入 reportId={} outcome={} traceId={}", reportId, outcome, traceId);
        return reportRepository.findById(reportId).orElseThrow();
    }

    private static String jsonQuote(String text) {
        if (text == null || text.isBlank()) {
            return "null";
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
