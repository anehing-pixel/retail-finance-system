package com.company.retail.titlerelease.application.statemachine;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.statemachine.StateMachineFactories;
import com.company.retail.titlerelease.domain.ReceiptAuthority;
import com.company.retail.titlerelease.domain.ReleaseOrder;
import com.company.retail.titlerelease.domain.ReleaseOrderStatus;
import com.company.retail.titlerelease.domain.TitleRetention;
import com.company.retail.titlerelease.domain.TitleRetentionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.config.builders.StateMachineConfigBuilder;
import org.springframework.statemachine.transition.Transition;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 解除链路状态机引擎层测试（R04 §3.5；两态分离 ADR-RF-006；C-11 权威分级）。
 *
 * <p>覆盖：① 两态分离——出具文件不改保留状态；② 代录不能直达解押完成；
 * ③ 外部回执方可闭环；④ 领域异常原样透传（错误码不因接入引擎而改变）；
 * ⑤ 终态不可再迁移；⑥ 引擎迁移边与领域状态图逐边对齐。</p>
 */
class ReleaseStateMachineEngineTest {

    private static StateMachineFactory<ReleaseOrderStatus, ReleaseOrderEvent> orderFactory() throws Exception {
        return StateMachineFactories.create(new ReleaseOrderStateMachineConfigurer());
    }

    private static StateMachineFactory<TitleRetentionStatus, TitleRetentionEvent> retentionFactory()
            throws Exception {
        return StateMachineFactories.create(new TitleRetentionStateMachineConfigurer());
    }

    private static ReleaseTransitionService service() throws Exception {
        return new ReleaseTransitionService(orderFactory(), retentionFactory());
    }

    private static TitleRetention registeredRetention() {
        return TitleRetention.restore(7001L, 9001L, "VIN-TEST-0001", "REGISTERED", "REGISTERED",
                Instant.now(), null, 1);
    }

    @Test
    void issueDocuments_doesNotTouchRetentionState() throws Exception {
        ReleaseTransitionService transitions = service();
        ReleaseOrder order = ReleaseOrder.create(9001L, 7001L, "EVENT", 42L);
        TitleRetention retention = registeredRetention();

        transitions.prepareDocuments(order);
        transitions.issueDocuments(order);

        assertEquals(ReleaseOrderStatus.DOCUMENTS_ISSUED, order.status());
        assertEquals(TitleRetentionStatus.REGISTERED, retention.status(),
                "出具解除文件不得改写保留状态（两态分离 B-6 / ADR-RF-006）");
    }

    @Test
    void manualWitness_cannotDirectlyConfirmRegistration() throws Exception {
        ReleaseTransitionService transitions = service();
        ReleaseOrder order = ReleaseOrder.create(9001L, 7001L, "EVENT", 42L);

        transitions.prepareDocuments(order);
        transitions.issueDocuments(order);
        transitions.recordManualWitness(order);
        assertEquals(ReleaseOrderStatus.MANUAL_RECORDED, order.status(), "代录只是中间态");

        // 代录后必须补外部回执才能闭环；内部见证权威不足 → 领域按 C-11 拒绝
        BusinessException e = assertThrows(BusinessException.class,
                () -> transitions.confirmRegistration(order, ReceiptAuthority.INTERNAL_WITNESS));
        assertEquals(RFErrorCode.RF_TITLE_002, e.getErrorCode());
        assertEquals(ReleaseOrderStatus.MANUAL_RECORDED, order.status(), "被拒绝后状态不得变化");
    }

    @Test
    void partnerSelfReport_isRejectedByDomain() throws Exception {
        ReleaseTransitionService transitions = service();
        ReleaseOrder order = ReleaseOrder.create(9001L, 7001L, "EVENT", 42L);
        transitions.prepareDocuments(order);
        transitions.issueDocuments(order);
        transitions.submitExternal(order, "SUB-9001");

        BusinessException e = assertThrows(BusinessException.class,
                () -> transitions.confirmRegistration(order, ReceiptAuthority.PARTNER_SELF_REPORT));
        assertEquals(RFErrorCode.RF_TITLE_002, e.getErrorCode(),
                "经销商/消费者自证不得采信（X-10）");
    }

    @Test
    void externalReceipt_closesOrderAndRetention() throws Exception {
        ReleaseTransitionService transitions = service();
        ReleaseOrder order = ReleaseOrder.create(9001L, 7001L, "EVENT", 42L);
        TitleRetention retention = registeredRetention();

        transitions.prepareDocuments(order);
        transitions.issueDocuments(order);
        transitions.submitExternal(order, "SUB-9001");
        transitions.confirmRegistration(order, ReceiptAuthority.EXTERNAL_REGISTRY);
        assertEquals(ReleaseOrderStatus.REGISTRATION_CONFIRMED, order.status());

        transitions.markReleaseRegistered(retention, order.status(), ReceiptAuthority.EXTERNAL_REGISTRY);
        assertEquals(TitleRetentionStatus.RELEASE_REGISTERED, retention.status());

        transitions.close(order);
        transitions.close(retention);
        assertEquals(ReleaseOrderStatus.CLOSED, order.status());
        assertEquals(TitleRetentionStatus.CLOSED, retention.status());
    }

    @Test
    void terminalOrder_rejectsFurtherTransition() throws Exception {
        ReleaseTransitionService transitions = service();
        ReleaseOrder order = ReleaseOrder.create(9001L, 7001L, "EVENT", 42L);
        transitions.prepareDocuments(order);
        transitions.issueDocuments(order);
        transitions.submitExternal(order, "SUB-9001");
        transitions.confirmRegistration(order, ReceiptAuthority.EXTERNAL_REGISTRY);
        transitions.close(order);

        assertThrows(BusinessException.class, () -> transitions.close(order));
        assertEquals(ReleaseOrderStatus.CLOSED, order.status(), "终态不可再迁移");
    }

    @Test
    void engineEdges_areExactlyDomainEdgesMinusDeclaredUnsupported() throws Exception {
        ReleaseOrderStateMachineConfigurer orderConfigurer = new ReleaseOrderStateMachineConfigurer();
        StateMachineFactory<ReleaseOrderStatus, ReleaseOrderEvent> orderMachineFactory =
                StateMachineFactories.create(orderConfigurer);
        StateMachine<ReleaseOrderStatus, ReleaseOrderEvent> orderMachine = orderMachineFactory.getStateMachine();

        Set<String> engineEdges = new HashSet<>();
        for (Transition<ReleaseOrderStatus, ReleaseOrderEvent> transition : orderMachine.getTransitions()) {
            engineEdges.add(transition.getSource().getId().name() + "->" + transition.getTarget().getId().name());
        }
        Set<String> domainEdges = new HashSet<>();
        for (Map.Entry<ReleaseOrderStatus, Set<ReleaseOrderStatus>> entry
                : ReleaseOrderStatus.graph().entrySet()) {
            for (ReleaseOrderStatus target : entry.getValue()) {
                domainEdges.add(entry.getKey().name() + "->" + target.name());
            }
        }
        Set<String> expected = new HashSet<>(domainEdges);
        expected.removeAll(orderConfigurer.unsupportedEdges());

        assertEquals(expected, engineEdges, "解除订单引擎迁移边必须与领域状态图逐边对齐");

        TitleRetentionStateMachineConfigurer retentionConfigurer = new TitleRetentionStateMachineConfigurer();
        StateMachineFactory<TitleRetentionStatus, TitleRetentionEvent> retentionMachineFactory =
                StateMachineFactories.create(retentionConfigurer);
        StateMachine<TitleRetentionStatus, TitleRetentionEvent> retentionMachine =
                retentionMachineFactory.getStateMachine();

        Set<String> retentionEngineEdges = new HashSet<>();
        for (Transition<TitleRetentionStatus, TitleRetentionEvent> transition
                : retentionMachine.getTransitions()) {
            retentionEngineEdges.add(transition.getSource().getId().name()
                    + "->" + transition.getTarget().getId().name());
        }
        Set<String> retentionDomainEdges = new HashSet<>();
        for (Map.Entry<TitleRetentionStatus, Set<TitleRetentionStatus>> entry
                : TitleRetentionStatus.graph().entrySet()) {
            for (TitleRetentionStatus target : entry.getValue()) {
                retentionDomainEdges.add(entry.getKey().name() + "->" + target.name());
            }
        }
        Set<String> retentionExpected = new HashSet<>(retentionDomainEdges);
        retentionExpected.removeAll(retentionConfigurer.unsupportedEdges());

        assertEquals(retentionExpected, retentionEngineEdges, "保留引擎迁移边必须与领域状态图逐边对齐");
        assertNotNull(retentionMachine);
    }
}
