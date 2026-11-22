package com.company.retail.titlerelease;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.titlerelease.domain.ReleaseOrder;
import com.company.retail.titlerelease.domain.ReleaseOrderStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解除八步状态机穷举（R04 §3.5 ReleaseOrderStatus / R05 §6 八步）。
 */
class ReleaseOrderStateMachineTest {

    private static ReleaseOrder newOrder() {
        return ReleaseOrder.create(321L, 41L, "EVENT", 9001L);
    }

    @Test
    void creation_requiresLoanTerminalEvidence_inv3() {
        assertThrows(BusinessException.class, () -> ReleaseOrder.assertCreationAllowed(false));
        // 事件路径 / 人工挡板证据均满足 INV-3
        ReleaseOrder.assertCreationAllowed(true);
        assertEquals(ReleaseOrderStatus.CREATED, newOrder().status());
    }

    @Test
    void happyPath_eightSteps_exhaustivelyAdvance() {
        ReleaseOrder order = newOrder();
        // 第 2 步：CREATED（第 1 步 = 债权终态，由事件来源保证）
        assertEquals(ReleaseOrderStatus.CREATED, order.status());
        // 第 3 步：备齐四件套
        order.prepareDocuments();
        assertEquals(ReleaseOrderStatus.DOCUMENTS_PREPARED, order.status());
        // 第 4 步：発行并交付（文件出具 ≠ 解押完成）
        order.issueDocuments();
        assertEquals(ReleaseOrderStatus.DOCUMENTS_ISSUED, order.status());
        // 第 5 步：外部提交
        order.submitExternal("REG-2026-0001");
        assertEquals(ReleaseOrderStatus.EXTERNAL_SUBMITTED, order.status());
        assertEquals("REG-2026-0001", order.externalSubmissionRef());
        // 第 6 步：外部回执 → REGISTRATION_CONFIRMED（第 7 步保留改写、第 8 步关闭在服务编排）
        order.confirmRegistration(com.company.retail.titlerelease.domain.ReceiptAuthority.EXTERNAL_REGISTRY);
        assertEquals(ReleaseOrderStatus.REGISTRATION_CONFIRMED, order.status());
        assertEquals("EXTERNAL_REGISTRY", order.confirmationAuthority());
        order.close();
        assertEquals(ReleaseOrderStatus.CLOSED, order.status());
        assertTrue(order.status().isTerminal());
    }

    @Test
    void manualWitnessPath_intermediateStateOnly_needsExternalReceiptLater() {
        ReleaseOrder order = newOrder();
        order.prepareDocuments();
        order.issueDocuments();
        // 我方见证代录（X-9）：仅中间态
        order.recordManualWitness();
        assertEquals(ReleaseOrderStatus.MANUAL_RECORDED, order.status());
        // 代录本身不能确认完成（C-11）
        BusinessException e = assertThrows(BusinessException.class, () -> order.confirmRegistration(
                com.company.retail.titlerelease.domain.ReceiptAuthority.INTERNAL_WITNESS));
        assertEquals(RFErrorCode.RF_TITLE_002, e.getErrorCode());
        // 补外部回执后可推进
        order.confirmRegistration(com.company.retail.titlerelease.domain.ReceiptAuthority.EXTERNAL_REGISTRY);
        assertEquals(ReleaseOrderStatus.REGISTRATION_CONFIRMED, order.status());
    }

    @Test
    void invalidTransitions_areRejected_exhaustively() {
        ReleaseOrder created = newOrder();
        // CREATED 不得直达 DOCUMENTS_ISSUED / EXTERNAL_SUBMITTED / CLOSED
        assertThrows(BusinessException.class, created::issueDocuments);
        assertThrows(BusinessException.class,
                () -> created.submitExternal("REF-1"));
        assertThrows(BusinessException.class, created::close);

        ReleaseOrder prepared = newOrder();
        prepared.prepareDocuments();
        assertThrows(BusinessException.class, prepared::prepareDocuments); // 不可重复
        assertThrows(BusinessException.class, () -> prepared.submitExternal("REF-1")); // 未発行不得提交

        ReleaseOrder issued = newOrder();
        issued.prepareDocuments();
        issued.issueDocuments();
        // 未外部提交不得直接确认完成
        assertThrows(BusinessException.class, () -> issued.confirmRegistration(
                com.company.retail.titlerelease.domain.ReceiptAuthority.EXTERNAL_REGISTRY));

        ReleaseOrder closed = newOrder();
        closed.prepareDocuments();
        closed.issueDocuments();
        closed.submitExternal("REF-1");
        closed.confirmRegistration(com.company.retail.titlerelease.domain.ReceiptAuthority.EXTERNAL_REGISTRY);
        closed.close();
        // 终态不可再迁移（I-7）
        assertThrows(BusinessException.class, closed::close);
        assertThrows(BusinessException.class, () -> closed.confirmRegistration(
                com.company.retail.titlerelease.domain.ReceiptAuthority.EXTERNAL_REGISTRY));
    }

    @Test
    void submissionRef_isMandatory_forEvidence6() {
        ReleaseOrder issued = newOrder();
        issued.prepareDocuments();
        issued.issueDocuments();
        assertThrows(BusinessException.class, () -> issued.submitExternal(" "));
    }

    @Test
    void externalSubmission_isNotCompletion_twoStateSeparation() {
        // 文件出具 / 外部提交阶段（EXPIRED 可达态）不产生任何「解押完成」语义：
        // 聚合上不存在从 EXTERNAL_SUBMITTED 直达 CLOSED 的方法（编译期收口 + 运行期状态机双兜底）
        ReleaseOrder issued = newOrder();
        issued.prepareDocuments();
        issued.issueDocuments();
        issued.submitExternal("REF-1");
        assertThrows(BusinessException.class, issued::close);
        assertEquals(ReleaseOrderStatus.EXTERNAL_SUBMITTED, issued.status());
    }
}
