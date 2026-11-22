package com.company.retail.servicing.application;

import com.company.platform.calendar.BusinessCalendar;
import com.company.platform.exception.BusinessException;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.domain.exception.RFErrorCode;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 征信报送触发用例（BC-9 贷后 → BC-4 报送；C-05：报送周期 T+1、报送主 CIC）。
 *
 * <p><b>分工边界</b>：bureau-service 是报送<b>执行方</b>（integration.credit_bureau_reports
 * 的权威写者）；servicing 只做<b>触发登记与状态跟踪</b>——</p>
 * <ul>
 *   <li>INV-5 / RB-1：触发登记同样必须锚定<b>未撤销同意</b>（fail-closed 门禁，
 *       查不到/超时/撤回一律拒绝 RF_UW_003，无白名单旁路）；</li>
 *   <li>T+1 营业日口径：{@code target_biz_date = 营业日历.plusBusinessDays(今日, leadDays)}
 *       （C-05 裁决值，leadDays 默认 1，配置覆盖 P-12）；</li>
 *   <li>状态跟踪：以 outbox 事件 DPD_CHANGED(aggregate_key=loanId) 的 PENDING/SENT 为
 *       送达锚点（不跨域直读 bureau 真相源表）；</li>
 *   <li>uk_brt_loan_type：一债权一触发类型，重复登记 RF_CONFLICT_001。</li>
 * </ul>
 */
public class BureauReportTriggerAppService {

    public static final String TRIGGER_DELINQUENCY = "DELINQUENCY";

    private final BureauReportTriggerRepository triggerRepository;
    private final ConsentValidityGate consentValidityGate;
    private final OutboxTrackingPort outboxTrackingPort;
    private final BusinessCalendar businessCalendar;
    private final AuditWriter auditWriter;
    private final int reportLeadDays;

    public BureauReportTriggerAppService(BureauReportTriggerRepository triggerRepository,
                                         ConsentValidityGate consentValidityGate,
                                         OutboxTrackingPort outboxTrackingPort,
                                         BusinessCalendar businessCalendar,
                                         AuditWriter auditWriter,
                                         int reportLeadDays) {
        if (reportLeadDays < 1) {
            throw new IllegalArgumentException("报送提前天数至少 1（T+1 口径）");
        }
        this.triggerRepository = triggerRepository;
        this.consentValidityGate = consentValidityGate;
        this.outboxTrackingPort = outboxTrackingPort;
        this.businessCalendar = businessCalendar;
        this.auditWriter = auditWriter;
        this.reportLeadDays = reportLeadDays;
    }

    /** 登记逾期报送触发（T+1 营业日；INV-5 fail-closed 同意门禁）。 */
    @Transactional
    public BureauReportTriggerRepository.TriggerRow register(long loanId, long consentId,
                                                             String dpdStage, String actor,
                                                             String traceId) {
        if (loanId <= 0 || consentId <= 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    Map.of("reason", "LOAN_AND_CONSENT_REQUIRED"));
        }
        // INV-5 / RB-1：同意缺失/撤回/不可验证 → 一律拒绝（fail-closed，无白名单）
        if (!consentValidityGate.isValid(consentId)) {
            throw new BusinessException(RFErrorCode.RF_UW_003,
                    Map.of("reason", "CONSENT_INVALID_OR_WITHDRAWN",
                            "consentId", String.valueOf(consentId)));
        }
        // T+1 营业日（C-05）：跳过周末与 core.holidays 节假日
        LocalDate targetBizDate = businessCalendar.plusBusinessDays(LocalDate.now(), reportLeadDays);
        if (!triggerRepository.insert(loanId, dpdStage == null || dpdStage.isBlank()
                ? "M1" : dpdStage, TRIGGER_DELINQUENCY, consentId, targetBizDate, traceId)) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001, Map.of(
                    "reason", "REPORT_TRIGGER_DUPLICATE", "loanId", String.valueOf(loanId)));
        }
        BureauReportTriggerRepository.TriggerRow row = triggerRepository
                .findByLoanAndType(loanId, TRIGGER_DELINQUENCY)
                .orElseThrow(() -> new IllegalStateException("触发登记插入后未能回读 triggerId"));

        Map<String, Object> auditSnapshot = new LinkedHashMap<>();
        auditSnapshot.put("triggerId", row.triggerId());
        auditSnapshot.put("loanId", row.loanId());
        auditSnapshot.put("dpdStage", row.dpdStage());
        auditSnapshot.put("triggerType", row.triggerType());
        auditSnapshot.put("targetBizDate", row.targetBizDate().toString());
        auditWriter.write(actor, "STAFF", "REPORT_TRIGGER_REGISTERED", "BUREAU_REPORT_TRIGGER",
                String.valueOf(row.triggerId()), auditSnapshot, traceId);
        return row;
    }

    public Optional<BureauReportTriggerRepository.TriggerRow> get(long triggerId) {
        return triggerRepository.findById(triggerId);
    }

    public List<BureauReportTriggerRepository.TriggerRow> list(String statusOrNull) {
        return triggerRepository.list(statusOrNull);
    }

    /** 状态跟踪：触发登记 + 送达锚点（outbox 事件投递状态）。 */
    public Map<String, Object> track(long triggerId, String traceId) {
        BureauReportTriggerRepository.TriggerRow row = triggerRepository.findById(triggerId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        Map.of("reason", "TRIGGER_NOT_FOUND",
                                "triggerId", String.valueOf(triggerId))));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("triggerId", row.triggerId());
        data.put("loanId", row.loanId());
        data.put("dpdStage", row.dpdStage());
        data.put("triggerType", row.triggerType());
        data.put("targetBizDate", row.targetBizDate().toString());
        data.put("status", row.status());
        data.put("outboxEventStatus", outboxTrackingPort
                .latestEventStatus(OutboxPublisher.DPD_CHANGED, String.valueOf(row.loanId()))
                .orElse("NO_EVENT"));
        return data;
    }
}
