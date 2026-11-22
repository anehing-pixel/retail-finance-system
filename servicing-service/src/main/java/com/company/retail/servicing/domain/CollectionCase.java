package com.company.retail.servicing.domain;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 催收案件聚合（R04 A-13；表 core.collection_cases + core.collection_actions，V9/V14）。
 *
 * <p><b>不变式</b>：</p>
 * <ul>
 *   <li>状态变更只能走聚合方法，禁止直接 {@code UPDATE ... SET status}（R00 §5）；</li>
 *   <li>每个动作生成一条留痕（R08 §7 append-only）；</li>
 *   <li>关案必须登记结果（REPAID / UNRECOVERABLE），不允许无结果关案；</li>
 *   <li>终态 CLOSED 不可再迁移（I-7）。</li>
 * </ul>
 *
 * <p><b>不可配置旁路（R08 RB 清单同源）</b>：催收必经案件——本聚合只接受「案件上的动作」，
 * 没有案件就没有聚合，不存在任何开关/白名单可以让「无案件直接操作客户」成立。</p>
 */
public class CollectionCase {

    /** 关案结果：已回收 / 无法回收（结果流转显式登记，二者互斥）。 */
    public static final String OUTCOME_REPAID = "REPAID";
    public static final String OUTCOME_UNRECOVERABLE = "UNRECOVERABLE";

    private final long caseId;
    private final long loanId;
    private final String dpdStage;
    private CollectionCaseStatus status;
    private String assignedTo;
    private final long version;

    public CollectionCase(long caseId, long loanId, String dpdStage, CollectionCaseStatus status,
                          String assignedTo, long version) {
        this.caseId = caseId;
        this.loanId = loanId;
        this.dpdStage = dpdStage;
        this.status = status;
        this.assignedTo = assignedTo;
        this.version = version;
    }

    /** 催收动作留痕（聚合产生的领域值对象，application 层落库）。 */
    public record CollectionAction(CollectionActionType actionType, String outcome, String note,
                                   String actor, String traceId) {
    }

    /** 分案：OPEN → ASSIGNED，登记负责人。 */
    public CollectionAction assign(String assignee, String actor, String traceId) {
        requireTransition(CollectionActionType.ASSIGN);
        this.assignedTo = requireActor(assignee, "assignee");
        this.status = CollectionCaseStatus.ASSIGNED;
        return new CollectionAction(CollectionActionType.ASSIGN, null, null, actor, traceId);
    }

    /** 联系：ASSIGNED → IN_CONTACT。 */
    public CollectionAction contact(String note, String actor, String traceId) {
        requireTransition(CollectionActionType.CONTACT);
        this.status = CollectionCaseStatus.IN_CONTACT;
        return new CollectionAction(CollectionActionType.CONTACT, null, note, actor, traceId);
    }

    /** 承诺：IN_CONTACT → PROMISED。 */
    public CollectionAction promise(String note, String actor, String traceId) {
        requireTransition(CollectionActionType.PROMISE);
        this.status = CollectionCaseStatus.PROMISED;
        return new CollectionAction(CollectionActionType.PROMISE, null, note, actor, traceId);
    }

    /** 承诺未履约回联系：PROMISED → IN_CONTACT。 */
    public CollectionAction promiseBroken(String note, String actor, String traceId) {
        requireTransitionTo(CollectionCaseStatus.IN_CONTACT, "PROMISE_BROKEN");
        this.status = CollectionCaseStatus.IN_CONTACT;
        return new CollectionAction(CollectionActionType.CONTACT, null, note, actor, traceId);
    }

    /** 关案：任一在途状态 → CLOSED，必须登记结果。 */
    public CollectionAction close(String outcome, String note, String actor, String traceId) {
        if (!OUTCOME_REPAID.equals(outcome) && !OUTCOME_UNRECOVERABLE.equals(outcome)) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    Map.of("reason", "CLOSE_OUTCOME_REQUIRED",
                            "allowed", OUTCOME_REPAID + "/" + OUTCOME_UNRECOVERABLE));
        }
        requireTransition(CollectionActionType.CLOSE);
        this.status = CollectionCaseStatus.CLOSED;
        return new CollectionAction(CollectionActionType.CLOSE, outcome, note, actor, traceId);
    }

    private void requireTransition(CollectionActionType action) {
        requireTransitionTo(action.targetStatus(), action.name());
    }

    private void requireTransitionTo(CollectionCaseStatus target, String operation) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(RFErrorCode.RF_VALID_002, Map.of(
                    "reason", "COLLECTION_CASE_STATUS_TRANSITION_ILLEGAL",
                    "operation", operation,
                    "from", status.name(),
                    "to", target.name(),
                    "caseId", String.valueOf(caseId)));
        }
    }

    private static String requireActor(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", field));
        }
        return value;
    }

    /** 状态变更后的持久化载荷（infrastructure 据此落库，带乐观锁版本）。 */
    public Map<String, Object> persistencePatch() {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("caseId", caseId);
        patch.put("status", status.name());
        patch.put("assignedTo", assignedTo);
        patch.put("version", version);
        return patch;
    }

    /** 案件进入关案时设置关案时间戳的标记（infrastructure 读取状态判断）。 */
    public boolean isClosed() {
        return status.isTerminal();
    }

    public long caseId() {
        return caseId;
    }

    public long loanId() {
        return loanId;
    }

    public String dpdStage() {
        return dpdStage;
    }

    public CollectionCaseStatus status() {
        return status;
    }

    public String assignedTo() {
        return assignedTo;
    }

    public long version() {
        return version;
    }
}
