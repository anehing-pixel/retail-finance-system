package com.company.retail.titlerelease.application.statemachine;

import com.company.retail.statemachine.DomainStateMachineConfigurer;
import com.company.retail.titlerelease.domain.ReceiptAuthority;
import com.company.retail.titlerelease.domain.ReleaseOrderStatus;
import com.company.retail.titlerelease.domain.TitleRetention;
import com.company.retail.titlerelease.domain.TitleRetentionStatus;
import org.springframework.statemachine.StateContext;

import java.util.Map;
import java.util.Set;

/**
 * 所有权保留状态机引擎配置（R04 §3.5；两态分离 ADR-RF-006）。
 *
 * <p>状态图取自 {@link TitleRetentionStatus#graph()}（领域唯一事实源）。</p>
 *
 * <p><b>为什么这台机器只有两条边</b>：保留状态的迁移入口只有「外部回执到位后改写」
 * 与「证据链闭合后关闭」；<b>登记是创建动作</b>（直接落库为 REGISTERED，不存在从
 * ACTIVE 迁移过去的路径），<b>取消无用例</b>。按「不猜业务口径」原则显式登记为未接线，
 * 而不是臆造用例。</p>
 */
public class TitleRetentionStateMachineConfigurer
        extends DomainStateMachineConfigurer<TitleRetentionStatus, TitleRetentionEvent> {

    /** 事件头：保留聚合（进程内传递）。 */
    public static final String HEADER_RETENTION = "retention";
    /** 事件头：解除订单状态（INV-3 唯一入口的锚点）。 */
    public static final String HEADER_ORDER_STATUS = "orderStatus";
    /** 事件头：回执权威（C-11 权威分级）。 */
    public static final String HEADER_AUTHORITY = "authority";

    @Override
    protected Map<TitleRetentionStatus, Set<TitleRetentionStatus>> domainGraph() {
        return TitleRetentionStatus.graph();
    }

    @Override
    protected Class<TitleRetentionStatus> stateType() {
        return TitleRetentionStatus.class;
    }

    @Override
    protected TitleRetentionStatus initialState() {
        return TitleRetentionStatus.ACTIVE;
    }

    @Override
    protected TitleRetentionEvent eventFor(TitleRetentionStatus source, TitleRetentionStatus target) {
        return switch (target) {
            case RELEASE_REGISTERED -> TitleRetentionEvent.MARK_RELEASE_REGISTERED;
            case CLOSED -> TitleRetentionEvent.CLOSE;
            // 领域尚未提供变更入口的边（见 unsupportedEdges）
            case REGISTERED, CONTRACTUAL_ONLY, CANCELLED, ACTIVE, PENDING_REGISTRATION, NOT_REQUIRED -> null;
        };
    }

    @Override
    protected Set<String> unsupportedEdges() {
        // 登记属创建动作（直接落库为 REGISTERED / CONTRACTUAL_ONLY），不存在迁移路径；取消无用例
        return Set.of(
                "ACTIVE->REGISTERED",
                "ACTIVE->CONTRACTUAL_ONLY",
                "ACTIVE->CANCELLED",
                "PENDING_REGISTRATION->REGISTERED",
                "PENDING_REGISTRATION->CONTRACTUAL_ONLY",
                "PENDING_REGISTRATION->CANCELLED");
    }

    @Override
    protected boolean allowed(StateContext<TitleRetentionStatus, TitleRetentionEvent> context) {
        TitleRetention retention = retentionOf(context);
        if (retention == null || context.getTarget() == null) {
            return false;
        }
        // 领域合法性二次核对（迁移边本就来自领域图，这里对聚合实时状态再核一次）
        return retention.status().canTransitionTo(context.getTarget().getId());
    }

    @Override
    protected void apply(StateContext<TitleRetentionStatus, TitleRetentionEvent> context) {
        TitleRetention retention = retentionOf(context);
        if (retention == null) {
            throw new IllegalStateException("保留状态机事件缺少聚合上下文");
        }
        applyRetention(retention, context.getEvent(), context.getMessageHeaders());
    }

    /**
     * 事件 → 领域变更入口（引擎动作与用例层共用同一份派发，杜绝两处漂移）。
     */
    public static void applyRetention(TitleRetention retention, TitleRetentionEvent event,
                                      Map<String, Object> headers) {
        switch (event) {
            case MARK_RELEASE_REGISTERED -> retention.markReleaseRegistered(
                    (ReleaseOrderStatus) headers.get(HEADER_ORDER_STATUS),
                    (ReceiptAuthority) headers.get(HEADER_AUTHORITY));
            case CLOSE -> retention.close();
        }
    }

    private static TitleRetention retentionOf(StateContext<TitleRetentionStatus, TitleRetentionEvent> context) {
        Object value = context.getMessageHeader(HEADER_RETENTION);
        return value instanceof TitleRetention retention ? retention : null;
    }
}
