package com.company.retail.bureau.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.bureau.application.BureauReportRepository.ReportRow;
import com.company.retail.bureau.domain.ReportStatus;
import com.company.retail.domain.exception.RFErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * 报送执行用例（BC-4 报送闭环的执行核心；消费者与报送日批共用，ADR-RF-005 链路二）。
 *
 * <p><b>触发执行</b>（DPD_CHANGED 事件 / 报送日批扫描到期触发共用）：</p>
 * <ul>
 *   <li>业务幂等（R09 §6 第三层）：uk_report_loan_trigger 同 (loan, trigger) 已有
 *       SENT 留痕 → 跳过不重报；REJECTED 留痕 → 人工重触发语义走 resend；</li>
 *   <li>INV-5 / RB-1：同意门禁 fail-closed——RF_UW_003 时触发置 BLOCKED 并<b>正常返回</b>
 *       （永久性拦截不进框架重试）；</li>
 *   <li>成功 → 触发置 CONSUMED；报送内部状态流转（PENDING → SENT/REJECTED）与
 *       CREDIT_BUREAU_REPORT_SENT 事件由 {@link BureauReportAppService} 负责。</li>
 * </ul>
 *
 * <p><b>结清报送</b>（LOAN_SETTLED，BC-6 → BC-4）：payload 只有业务主键，consentId
 * 经 {@link SettlementConsentAnchorPort} 解析（决定时同意锚点）后走同一执行通道。</p>
 */
public class BureauReportExecutionService {

    private static final Logger log = LoggerFactory.getLogger(BureauReportExecutionService.class);

    /** 结清报送触发值（ReportTrigger：SETTLED，BureauReportCommand javadoc 口径）。 */
    public static final String TRIGGER_SETTLED = "SETTLED";

    private final BureauReportAppService reportAppService;
    private final BureauReportRepository reportRepository;
    private final BureauTriggerPort triggerPort;
    private final SettlementConsentAnchorPort consentAnchorPort;

    public BureauReportExecutionService(BureauReportAppService reportAppService,
                                        BureauReportRepository reportRepository,
                                        BureauTriggerPort triggerPort,
                                        SettlementConsentAnchorPort consentAnchorPort) {
        this.reportAppService = reportAppService;
        this.reportRepository = reportRepository;
        this.triggerPort = triggerPort;
        this.consentAnchorPort = consentAnchorPort;
    }

    /** 执行结果（web/批摘要用，不含 PII）。 */
    public record ExecutionResult(long reportId, String outcome) {
    }

    /** outcome 语义：SENT=本次报送成功 / SKIP_ALREADY_SENT=幂等跳过 / BLOCKED=同意拦截 / PENDING=策略点关走人工。 */
    public static final String OUTCOME_SENT = "SENT";
    public static final String OUTCOME_SKIPPED = "SKIP_ALREADY_SENT";
    public static final String OUTCOME_BLOCKED = "BLOCKED";
    public static final String OUTCOME_PENDING = "PENDING";

    /**
     * 执行一条逾期报送触发（消费 DPD_CHANGED 与报送日批共用）。
     * 不抛同意拦截异常（BLOCKED 正常返回）；其余业务异常向上抛（框架重试语义）。
     */
    @Transactional
    public ExecutionResult executeTrigger(BureauTriggerPort.TriggerRow trigger, String traceId) {
        // 业务幂等：同 (loan, trigger) 已有留痕——SENT 跳过，REJECTED 重走挡板
        Optional<ReportRow> existing =
                reportRepository.findByLoanAndTrigger(trigger.loanId(), trigger.triggerType());
        if (existing.isPresent()) {
            return resolveExisting(trigger, existing.get(), traceId);
        }
        try {
            var sent = reportAppService.sendReport(
                    trigger.loanId(), trigger.consentId(), trigger.triggerType(), traceId);
            triggerPort.updateStatus(trigger.triggerId(), "CONSUMED");
            log.info("报送触发执行完成 triggerId={} loanId={} reportId={} traceId={}",
                    trigger.triggerId(), trigger.loanId(), sent.reportId(), traceId);
            return new ExecutionResult(sent.reportId(),
                    ReportStatus.SENT.name().equals(sent.status()) ? OUTCOME_SENT : OUTCOME_PENDING);
        } catch (BusinessException e) {
            if (RFErrorCode.RF_UW_003 == e.getErrorCode()) {
                // INV-5：同意缺失/撤回 → 触发 BLOCKED（永久性拦截，不进框架重试）
                triggerPort.updateStatus(trigger.triggerId(), "BLOCKED");
                log.warn("报送触发被同意门禁拦截 triggerId={} loanId={} consentId={} traceId={}",
                        trigger.triggerId(), trigger.loanId(), trigger.consentId(), traceId);
                return new ExecutionResult(0, OUTCOME_BLOCKED);
            }
            if (RFErrorCode.RF_CONFLICT_001 == e.getErrorCode()) {
                // 并发兜底：另一消费者/批次已插入留痕 → 幂等跳过
                log.info("报送触发并发冲突，按幂等跳过 triggerId={} loanId={} traceId={}",
                        trigger.triggerId(), trigger.loanId(), traceId);
                return new ExecutionResult(0, OUTCOME_SKIPPED);
            }
            throw e;    // 其余异常交框架重试（RfEventDispatcher attempts/DEAD）
        }
    }

    /** 已有留痕时的幂等决策。 */
    private ExecutionResult resolveExisting(BureauTriggerPort.TriggerRow trigger, ReportRow row,
                                            String traceId) {
        if (ReportStatus.SENT.name().equals(row.status())) {
            // 重复事件/批重跑：不重复报送（R09 §6 幂等第三层），推进触发状态收口
            triggerPort.updateStatus(trigger.triggerId(), "CONSUMED");
            return new ExecutionResult(row.reportId(), OUTCOME_SKIPPED);
        }
        if (ReportStatus.REJECTED.name().equals(row.status())) {
            var resent = reportAppService.resend(row.reportId(), traceId);
            triggerPort.updateStatus(trigger.triggerId(), "CONSUMED");
            return new ExecutionResult(resent.reportId(),
                    ReportStatus.SENT.name().equals(resent.status()) ? OUTCOME_SENT : OUTCOME_PENDING);
        }
        // PENDING（策略点关闭走人工通道）：保持人工语义，不自动重报
        return new ExecutionResult(row.reportId(), OUTCOME_PENDING);
    }

    /**
     * 结清报送（消费 LOAN_SETTLED）：同意锚点解析 → 同一执行通道（trigger=SETTLED）。
     * 业务幂等：同 (loan, SETTLED) 已有 SENT 留痕即跳过。
     */
    @Transactional
    public ExecutionResult executeSettlement(long loanId, String traceId) {
        Optional<ReportRow> existing = reportRepository.findByLoanAndTrigger(loanId, TRIGGER_SETTLED);
        if (existing.isPresent() && ReportStatus.SENT.name().equals(existing.get().status())) {
            return new ExecutionResult(existing.get().reportId(), OUTCOME_SKIPPED);
        }
        if (existing.isPresent() && ReportStatus.REJECTED.name().equals(existing.get().status())) {
            var resent = reportAppService.resend(existing.get().reportId(), traceId);
            return new ExecutionResult(resent.reportId(),
                    ReportStatus.SENT.name().equals(resent.status()) ? OUTCOME_SENT : OUTCOME_PENDING);
        }
        // INV-5：consent_id NOT NULL——LOAN_SETTLED payload 无 consentId，按锚点链解析
        long consentId = consentAnchorPort.resolveConsentId(loanId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001, Map.of(
                        "reason", "SETTLEMENT_CONSENT_ANCHOR_NOT_FOUND",
                        "loanId", String.valueOf(loanId))));
        var sent = reportAppService.sendReport(loanId, consentId, TRIGGER_SETTLED, traceId);
        log.info("结清报送完成 loanId={} reportId={} traceId={}", loanId, sent.reportId(), traceId);
        return new ExecutionResult(sent.reportId(),
                ReportStatus.SENT.name().equals(sent.status()) ? OUTCOME_SENT : OUTCOME_PENDING);
    }
}
