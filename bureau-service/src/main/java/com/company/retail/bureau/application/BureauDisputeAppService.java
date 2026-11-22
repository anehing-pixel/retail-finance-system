package com.company.retail.bureau.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.bureau.acl.BureauAdapter;
import com.company.retail.bureau.acl.BureauDisputeCommand;
import com.company.retail.bureau.acl.DisputeOutcome;
import com.company.retail.bureau.domain.CreditBureau;
import com.company.retail.bureau.domain.CreditBureauReportingPolicy;
import com.company.retail.bureau.domain.DisputeStatus;
import com.company.retail.domain.exception.RFErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 征信异议申诉用例（BC-4，ADR-RF-005 链路三，C-05：SLA 30 日可配）。
 *
 * <ul>
 *   <li>受理：due_at = 受理时间 + SLA（策略点给出）；customer_ref 只存引用码不落 PII；</li>
 *   <li>处理留痕 + 订正报送（经 ACL 适配器，报文留痕）；</li>
 *   <li>超期（due_at 已过仍 OPEN）→ {@code RF_BUREAU_002} 合规升级；</li>
 *   <li>岗位配置层 SOD 对 6（征信报送 ↔ 异议申诉处理不相容岗）由 auth 角色配置承担。</li>
 * </ul>
 */
public class BureauDisputeAppService {

    private static final Logger log = LoggerFactory.getLogger(BureauDisputeAppService.class);

    private final BureauDisputeRepository disputeRepository;
    private final BureauMessageLogRepository messageLogRepository;
    private final Map<CreditBureau, BureauAdapter> adapters;
    private final CreditBureauReportingPolicy reportingPolicy;

    public BureauDisputeAppService(BureauDisputeRepository disputeRepository,
                                   BureauMessageLogRepository messageLogRepository,
                                   Map<CreditBureau, BureauAdapter> adapters,
                                   CreditBureauReportingPolicy reportingPolicy) {
        this.disputeRepository = disputeRepository;
        this.messageLogRepository = messageLogRepository;
        this.adapters = adapters;
        this.reportingPolicy = reportingPolicy;
    }

    /** 受理异议工单（due_at = 受理 + SLA）。 */
    @Transactional
    public DisputeView open(String customerRef, String bureauName, String type, String traceId) {
        if (customerRef == null || customerRef.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", "customerRef"));
        }
        if (type == null || type.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", "type"));
        }
        CreditBureau bureau = CreditBureau.valueOf(safeBureau(bureauName));
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime dueAt = now.plusDays(reportingPolicy.disputeSlaDays());
        long disputeId = disputeRepository.insert(customerRef, bureau.name(), type, dueAt, traceId);
        messageLogRepository.append("OUTBOUND", bureau.name(), String.valueOf(disputeId),
                "{\"type\":\"DISPUTE_OPEN\",\"disputeId\":" + disputeId + "}", traceId);
        log.info("异议工单受理 disputeId={} dueAt={}（SLA {} 日）", disputeId, dueAt, reportingPolicy.disputeSlaDays());
        return new DisputeView(disputeId, bureau.name(), type, dueAt.toString(), DisputeStatus.OPEN.name());
    }

    /** 处理异议（订正报送经 ACL；处理留痕进报文留痕表）。 */
    @Transactional
    public DisputeView handle(long disputeId, String resolutionRef, String traceId) {
        BureauDisputeRepository.DisputeRow row = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        Map.of("reason", "DISPUTE_NOT_FOUND", "disputeId", String.valueOf(disputeId))));
        if (DisputeStatus.RESOLVED.name().equals(row.status())) {
            throw new BusinessException(RFErrorCode.RF_VALID_002, Map.of("reason", "ALREADY_RESOLVED"));
        }
        if (row.dueAt() != null && row.dueAt().isBefore(OffsetDateTime.now())) {
            // C-05 SLA：超期未处理 → 合规升级（RF_BUREAU_002）
            throw new BusinessException(RFErrorCode.RF_BUREAU_002,
                    Map.of("disputeId", String.valueOf(disputeId), "dueAt", row.dueAt().toString()));
        }
        CreditBureau bureau = CreditBureau.valueOf(row.bureau());
        DisputeOutcome outcome = adapters.get(bureau).fileDispute(
                new BureauDisputeCommand(disputeId, bureau, resolutionRef));
        messageLogRepository.append("INBOUND", bureau.name(), String.valueOf(disputeId),
                outcome.rawMessage(), traceId);
        disputeRepository.markResolved(disputeId,
                outcome.registryRef() == null ? resolutionRef : outcome.registryRef(), traceId);
        log.info("异议工单处理完成 disputeId={} accepted={}", disputeId, outcome.accepted());
        return new DisputeView(disputeId, bureau.name(), row.type(),
                row.dueAt() == null ? null : row.dueAt().toString(), DisputeStatus.RESOLVED.name());
    }

    private static String safeBureau(String name) {
        if (name == null || name.isBlank()) {
            return CreditBureau.CIC.name(); // C-05：异议默认走主机构 CIC
        }
        try {
            CreditBureau.valueOf(name);
            return name;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", "bureau", "value", name));
        }
    }

    /** 视图行（web 层用；customer_ref 不回显——只回 ID）。 */
    public record DisputeView(long disputeId, String bureau, String type, String dueAt, String status) {
    }
}
