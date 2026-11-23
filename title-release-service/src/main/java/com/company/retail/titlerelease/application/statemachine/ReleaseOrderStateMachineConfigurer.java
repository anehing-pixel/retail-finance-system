package com.company.retail.titlerelease.application.statemachine;

import com.company.retail.statemachine.DomainStateMachineConfigurer;
import com.company.retail.titlerelease.domain.ReceiptAuthority;
import com.company.retail.titlerelease.domain.ReleaseOrder;
import com.company.retail.titlerelease.domain.ReleaseOrderStatus;
import org.springframework.statemachine.StateContext;

import java.util.Map;
import java.util.Set;

/**
 * 解除订单状态机引擎配置（R04 §3.5，八步 R05 §6）。
 *
 * <p>状态图取自 {@link ReleaseOrderStatus#graph()}（领域唯一事实源）。
 * 引擎负责事件分发、非法事件 fail-closed 拒绝、守卫二次核对与动作编排；
 * 领域仍是合法性的最终裁决者。</p>
 *
 * <p><b>两态分离（ADR-RF-006）在引擎层的落点</b>：本机只管解除订单，
 * 「出具文件」只改订单状态、不触碰保留聚合——保留状态由另一台机器
 * （{@link TitleRetentionStateMachineConfigurer}）在拿到外部回执后才允许推进。</p>
 */
public class ReleaseOrderStateMachineConfigurer
        extends DomainStateMachineConfigurer<ReleaseOrderStatus, ReleaseOrderEvent> {

    /** 事件头：解除订单聚合（进程内传递）。 */
    public static final String HEADER_ORDER = "order";
    /** 事件头：外部申请受理编号（证据链⑥）。 */
    public static final String HEADER_SUBMISSION_REF = "submissionRef";
    /** 事件头：回执权威。 */
    public static final String HEADER_AUTHORITY = "authority";

    @Override
    protected Map<ReleaseOrderStatus, Set<ReleaseOrderStatus>> domainGraph() {
        return ReleaseOrderStatus.graph();
    }

    @Override
    protected Class<ReleaseOrderStatus> stateType() {
        return ReleaseOrderStatus.class;
    }

    @Override
    protected ReleaseOrderStatus initialState() {
        return ReleaseOrderStatus.CREATED;
    }

    @Override
    protected ReleaseOrderEvent eventFor(ReleaseOrderStatus source, ReleaseOrderStatus target) {
        return switch (target) {
            case DOCUMENTS_PREPARED -> ReleaseOrderEvent.PREPARE_DOCUMENTS;
            case DOCUMENTS_ISSUED -> ReleaseOrderEvent.ISSUE_DOCUMENTS;
            case EXTERNAL_SUBMITTED -> ReleaseOrderEvent.SUBMIT_EXTERNAL;
            case MANUAL_RECORDED -> ReleaseOrderEvent.RECORD_MANUAL_WITNESS;
            case REGISTRATION_CONFIRMED -> ReleaseOrderEvent.CONFIRM_REGISTRATION;
            case CLOSED -> ReleaseOrderEvent.CLOSE;
            case REJECTED -> ReleaseOrderEvent.REJECT;
            // 领域尚未提供变更入口的边（见 unsupportedEdges）
            case EXPIRED, CREATED -> null;
        };
    }

    @Override
    protected Set<String> unsupportedEdges() {
        // 解除文件过期在领域层没有变更入口（无用例）
        return Set.of("DOCUMENTS_ISSUED->EXPIRED", "EXTERNAL_SUBMITTED->EXPIRED");
    }

    @Override
    protected boolean allowed(StateContext<ReleaseOrderStatus, ReleaseOrderEvent> context) {
        ReleaseOrder order = orderOf(context);
        if (order == null || context.getTarget() == null) {
            return false;
        }
        // 领域合法性二次核对（迁移边本就来自领域图，这里对聚合实时状态再核一次）
        return order.status().canTransitionTo(context.getTarget().getId());
    }

    @Override
    protected void apply(StateContext<ReleaseOrderStatus, ReleaseOrderEvent> context) {
        ReleaseOrder order = orderOf(context);
        if (order == null) {
            throw new IllegalStateException("解除订单状态机事件缺少聚合上下文");
        }
        applyOrder(order, context.getEvent(), context.getMessageHeaders());
    }

    /**
     * 事件 → 领域变更入口（引擎动作与用例层共用同一份派发，杜绝两处漂移）。
     */
    public static void applyOrder(ReleaseOrder order, ReleaseOrderEvent event, Map<String, Object> headers) {
        switch (event) {
            case PREPARE_DOCUMENTS -> order.prepareDocuments();
            case ISSUE_DOCUMENTS -> order.issueDocuments();
            case SUBMIT_EXTERNAL -> order.submitExternal((String) headers.get(HEADER_SUBMISSION_REF));
            case RECORD_MANUAL_WITNESS -> order.recordManualWitness();
            case CONFIRM_REGISTRATION -> order.confirmRegistration((ReceiptAuthority) headers.get(HEADER_AUTHORITY));
            case CLOSE -> order.close();
            case REJECT -> order.reject();
        }
    }

    private static ReleaseOrder orderOf(StateContext<ReleaseOrderStatus, ReleaseOrderEvent> context) {
        Object value = context.getMessageHeader(HEADER_ORDER);
        return value instanceof ReleaseOrder order ? order : null;
    }
}
