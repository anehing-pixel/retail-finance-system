package com.company.retail.servicing.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.servicing.domain.CollectionActionType;
import com.company.retail.servicing.domain.CollectionCase;
import com.company.retail.servicing.domain.CollectionCaseStatus;
import com.company.retail.servicing.domain.CollectionTimePolicy;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 催收案件用例（R04 A-13；R08：催收必经案件，COLLECTION_HANDLE）。
 *
 * <p><b>不可配置旁路（硬红线）</b>：</p>
 * <ul>
 *   <li>催收动作只能落在<b>已存在的案件</b>上——案件不存在即拒绝，本规则没有开关、
 *       没有白名单，时段窗口如何配置都改变不了它；</li>
 *   <li>系统不提供任何绕过案件、直接对客户/债权执行催收的入口（用例签名即边界）；</li>
 *   <li>催收时段窗口 fail-closed（{@link CollectionTimePolicy}），窗口外一律拒绝。</li>
 * </ul>
 */
public class CollectionCaseAppService {

    private final CollectionCaseRepository caseRepository;
    private final CollectionActionRepository actionRepository;
    private final CollectionTimePolicy timePolicy;
    private final AuditWriter auditWriter;

    public CollectionCaseAppService(CollectionCaseRepository caseRepository,
                                    CollectionActionRepository actionRepository,
                                    CollectionTimePolicy timePolicy,
                                    AuditWriter auditWriter) {
        this.caseRepository = caseRepository;
        this.actionRepository = actionRepository;
        this.timePolicy = timePolicy;
        this.auditWriter = auditWriter;
    }

    /** 执行催收动作（分案/联系/承诺/关案；状态机走聚合方法，动作留痕 append-only）。
     *
     * @param assignee 分案负责人（ASSIGN 必填；B-9：actor 取验签身份，assignee 是业务负责人字段）
     * @param outcome  关案结果（CLOSE 必填：REPAID / UNRECOVERABLE）
     * @param note     备注留痕（不落 PII 明文）
     */
    @Transactional
    public ActionRow executeAction(long caseId, String actionTypeText, String assignee,
                                   String outcome, String note, String actor, String actorSubject,
                                   OffsetDateTime at, String traceId) {
        CollectionActionType actionType = parseActionType(actionTypeText);

        // ① 时段约束（fail-closed，不可配置关闭；只约束窗口，不存在 bypass 开关）
        if (!timePolicy.allows(at)) {
            throw new BusinessException(RFErrorCode.RF_VALID_002, Map.of(
                    "reason", "COLLECTION_TIME_WINDOW",
                    "windowStart", timePolicy.windowStart().toString(),
                    "windowEnd", timePolicy.windowEnd().toString(),
                    "zone", CollectionTimePolicy.ZONE_JST.getId()));
        }

        // ② 催收必经案件：无案件即拒绝（无任何配置可绕过）
        CollectionCaseRepository.CaseRow row = caseRepository.findById(caseId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_002, Map.of(
                        "reason", "COLLECTION_CASE_NOT_FOUND",
                        "caseId", String.valueOf(caseId))));
        if (CollectionCaseStatus.CLOSED.name().equals(row.status())) {
            throw new BusinessException(RFErrorCode.RF_VALID_002, Map.of(
                    "reason", "COLLECTION_CASE_CLOSED",
                    "caseId", String.valueOf(caseId)));
        }

        // ③ 状态机走聚合方法（非法迁移在此抛 RF_VALID_002）
        CollectionCase aggregate = new CollectionCase(row.caseId(), row.loanId(), row.dpdStage(),
                CollectionCaseStatus.valueOf(row.status()), row.assignedTo(), row.version());
        CollectionCase.CollectionAction action = switch (actionType) {
            case ASSIGN -> aggregate.assign(assignee, actor, traceId);
            case CONTACT -> aggregate.contact(note, actor, traceId);
            case PROMISE -> aggregate.promise(note, actor, traceId);
            case CLOSE -> aggregate.close(outcome, note, actor, traceId);
        };

        // ④ 落库：状态（乐观锁）+ 动作留痕（append-only）
        if (!caseRepository.updateStatus(aggregate.caseId(), aggregate.status().name(),
                aggregate.assignedTo(), aggregate.isClosed(), aggregate.version(), traceId)) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001, Map.of(
                    "reason", "COLLECTION_CASE_CONCURRENT_UPDATE",
                    "caseId", String.valueOf(caseId)));
        }
        actionRepository.insert(caseId, actionType.name(), action.outcome(), action.note(),
                actor, actorSubject, traceId);

        Map<String, Object> auditSnapshot = new LinkedHashMap<>();
        auditSnapshot.put("caseId", caseId);
        auditSnapshot.put("loanId", row.loanId());
        auditSnapshot.put("actionType", actionType.name());
        auditSnapshot.put("fromStatus", row.status());
        auditSnapshot.put("toStatus", aggregate.status().name());
        auditSnapshot.put("outcome", action.outcome());
        auditWriter.write(actor, actorSubject, "COLLECTION_ACTION", "COLLECTION_CASE",
                String.valueOf(caseId), auditSnapshot, traceId);

        return new ActionRow(caseId, actionType.name(), action.outcome(),
                aggregate.status().name());
    }

    public Optional<CollectionCaseRepository.CaseRow> get(long caseId) {
        return caseRepository.findById(caseId);
    }

    public List<CollectionCaseRepository.CaseRow> list(String statusOrNull) {
        return caseRepository.list(statusOrNull);
    }

    public List<CollectionActionRepository.ActionRow> actions(long caseId) {
        return actionRepository.listByCase(caseId);
    }

    private static CollectionActionType parseActionType(String text) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", "actionType"));
        }
        try {
            return CollectionActionType.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of(
                    "field", "actionType", "value", text));
        }
    }

    /** 动作执行结果视图（web 层用；不含 PII）。 */
    public record ActionRow(long caseId, String actionType, String outcome, String caseStatus) {
    }
}
