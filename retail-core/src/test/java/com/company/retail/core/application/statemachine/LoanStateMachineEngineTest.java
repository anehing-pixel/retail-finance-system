package com.company.retail.core.application.statemachine;

import com.company.retail.core.domain.Loan;
import com.company.retail.statemachine.StateMachineFactories;
import com.company.retail.core.domain.LoanStatus;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 债权状态机引擎层测试（R04 §3.4）。
 *
 * <p>覆盖：① 代付两段迁移由引擎驱动且领域状态同步；② 非法迁移 fail-closed（领域状态不变）；
 * ③ 终态不可再迁移；④ 逾期补救与代付失败重放；⑤ 引擎迁移边与领域状态图逐边对齐。</p>
 */
class LoanStateMachineEngineTest {

    private static StateMachineFactory<LoanStatus, LoanEvent> factory() throws Exception {
        return StateMachineFactories.create(new LoanStateMachineConfigurer());
    }

    private static LoanTransitionService service() throws Exception {
        return new LoanTransitionService(factory());
    }

    /** 造一条 ACTIVE 债权（前置态用领域方法直接构造，不经引擎）。 */
    private static Loan activeLoan() {
        Loan loan = Loan.create(9001L, 1_000_000L, 50_000L);
        loan.startDisbursement();
        loan.completeDisbursement(Instant.now());
        return loan;
    }

    @Test
    void disbursementFlow_isDrivenByEngine() throws Exception {
        LoanTransitionService transitions = service();
        Loan loan = Loan.create(9001L, 1_000_000L, 50_000L);

        transitions.startDisbursement(loan);
        assertEquals(LoanStatus.DISBURSING, loan.status(), "引擎迁移后领域状态应同步为 DISBURSING");

        transitions.completeDisbursement(loan, Instant.now());
        assertEquals(LoanStatus.ACTIVE, loan.status(), "引擎迁移后领域状态应同步为 ACTIVE");
    }

    @Test
    void illegalTransition_isRejectedAndStateUnchanged() throws Exception {
        LoanTransitionService transitions = service();
        Loan loan = Loan.create(9001L, 1_000_000L, 50_000L);

        // PENDING_DISBURSEMENT → SETTLED 不在领域状态图里
        assertThrows(IllegalStateException.class, () -> transitions.settle(loan, false, Instant.now()));
        assertEquals(LoanStatus.PENDING_DISBURSEMENT, loan.status(), "被拒绝的迁移不得改变领域状态");
    }

    @Test
    void settlement_movesToSettledTerminalState() throws Exception {
        LoanTransitionService transitions = service();
        Loan loan = activeLoan();
        loan.applyAllocation(loan.balance());

        transitions.settle(loan, false, Instant.now());
        assertEquals(LoanStatus.SETTLED, loan.status());
    }

    @Test
    void earlySettlement_movesToEarlySettledTerminalState() throws Exception {
        LoanTransitionService transitions = service();
        Loan loan = activeLoan();
        loan.applyAllocation(loan.balance());

        transitions.settle(loan, true, Instant.now());
        assertEquals(LoanStatus.EARLY_SETTLED, loan.status(), "提前结清与全部结清是两个不同终态（B-7）");
    }

    @Test
    void terminalState_rejectsFurtherTransition() throws Exception {
        LoanTransitionService transitions = service();
        Loan loan = activeLoan();
        loan.applyAllocation(loan.balance());
        transitions.settle(loan, false, Instant.now());

        assertThrows(IllegalStateException.class, () -> transitions.settle(loan, false, Instant.now()));
        assertEquals(LoanStatus.SETTLED, loan.status(), "终态不可再迁移");
    }

    @Test
    void cureFromDelinquent_returnsToActive() throws Exception {
        LoanTransitionService transitions = service();
        Loan loan = Loan.restore(1L, 9001L, LoanStatus.DELINQUENT, 1_000_000L, 50_000L,
                0L, 1_050_000L, Instant.now(), null, null, 0L, 0L, 1);

        transitions.cure(loan);
        assertEquals(LoanStatus.ACTIVE, loan.status(), "逾期补救应回到 ACTIVE");
    }

    @Test
    void disburseFailed_canBeReplayed() throws Exception {
        LoanTransitionService transitions = service();
        Loan loan = Loan.create(9001L, 1_000_000L, 50_000L);
        transitions.startDisbursement(loan);

        transitions.markDisburseFailed(loan);
        assertEquals(LoanStatus.DISBURSE_FAILED, loan.status());

        transitions.startDisbursement(loan);
        assertEquals(LoanStatus.DISBURSING, loan.status(), "失败态允许人工重放一次");
    }

    @Test
    void engineEdges_areExactlyDomainEdgesMinusDeclaredUnsupported() throws Exception {
        LoanStateMachineConfigurer configurer = new LoanStateMachineConfigurer();
        StateMachineFactory<LoanStatus, LoanEvent> factory = StateMachineFactories.create(configurer);
        StateMachine<LoanStatus, LoanEvent> machine = factory.getStateMachine();

        Set<String> engineEdges = new HashSet<>();
        for (Transition<LoanStatus, LoanEvent> transition : machine.getTransitions()) {
            engineEdges.add(transition.getSource().getId().name() + "->" + transition.getTarget().getId().name());
        }
        Set<String> domainEdges = new HashSet<>();
        for (Map.Entry<LoanStatus, Set<LoanStatus>> entry : LoanStatus.graph().entrySet()) {
            for (LoanStatus target : entry.getValue()) {
                domainEdges.add(entry.getKey().name() + "->" + target.name());
            }
        }
        Set<String> expected = new HashSet<>(domainEdges);
        expected.removeAll(configurer.unsupportedEdges());

        assertEquals(expected, engineEdges, "引擎迁移边必须与领域状态图逐边对齐（只允许显式登记的未接线边缺席）");
        assertTrue(expected.contains("PENDING_DISBURSEMENT->DISBURSING"), "代付迁移边必须在引擎中接线");
    }
}
