package com.company.retail.titlerelease;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.titlerelease.domain.ReceiptAuthority;
import com.company.retail.titlerelease.domain.ReleaseOrder;
import com.company.retail.titlerelease.domain.ReleaseOrderStatus;
import com.company.retail.titlerelease.domain.TitleRetention;
import com.company.retail.titlerelease.domain.TitleRetentionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两态分离（ADR-RF-006）与外部回执唯一权威（C-11 / INV-3 / X-10）。
 */
class TwoStateSeparationTest {

    private static TitleRetention retention() {
        return TitleRetention.register(321L, "VIN-12345", false);
    }

    private static ReleaseOrder issuedOrder() {
        ReleaseOrder order = ReleaseOrder.create(321L, 41L, "EVENT", 9001L);
        order.prepareDocuments();
        order.issueDocuments();
        return order;
    }

    @Test
    void documentsIssued_doesNotTouchRetentionStatus() {
        TitleRetention r = retention();
        // 聚合上不存在「文件出具改保留状态」的入口：DOCUMENTS_ISSUED 状态传入改写方法被硬拒
        ReleaseOrder order = issuedOrder();
        assertEquals(ReleaseOrderStatus.DOCUMENTS_ISSUED, order.status());
        BusinessException e = assertThrows(BusinessException.class,
                () -> r.markReleaseRegistered(order.status(), ReceiptAuthority.EXTERNAL_REGISTRY));
        assertEquals(RFErrorCode.RF_TITLE_002, e.getErrorCode());
        // 保留仍然有效（REGISTERED）
        assertEquals(TitleRetentionStatus.REGISTERED, r.status());
    }

    @Test
    void externalSubmission_doesNotTouchRetentionStatus() {
        TitleRetention r = retention();
        ReleaseOrder order = issuedOrder();
        order.submitExternal("REG-2026-0001");
        // EXTERNAL_SUBMITTED ≠ 解押完成
        assertThrows(BusinessException.class,
                () -> r.markReleaseRegistered(order.status(), ReceiptAuthority.EXTERNAL_REGISTRY));
        assertEquals(TitleRetentionStatus.REGISTERED, r.status());
    }

    @Test
    void manualWitness_neverConfirmsRelease_c11() {
        TitleRetention r = retention();
        ReleaseOrder order = issuedOrder();
        order.recordManualWitness(); // MANUAL_RECORDED
        // 代录不是权威：不得改写保留状态
        assertThrows(BusinessException.class,
                () -> r.markReleaseRegistered(order.status(), ReceiptAuthority.INTERNAL_WITNESS));
        assertEquals(TitleRetentionStatus.REGISTERED, r.status());
        assertEquals(ReleaseOrderStatus.MANUAL_RECORDED, order.status());
    }

    @Test
    void partnerSelfReport_isRejected_entirely_x10() {
        // PARTNER_SELF_REPORT 不可确认、不可代录、不可改写保留状态
        assertEquals(false, ReceiptAuthority.PARTNER_SELF_REPORT.canConfirmRegistration());
        assertEquals(false, ReceiptAuthority.PARTNER_SELF_REPORT.canRecordManual());
        ReleaseOrder order = issuedOrder();
        assertThrows(BusinessException.class,
                () -> order.confirmRegistration(ReceiptAuthority.PARTNER_SELF_REPORT));
        assertEquals(ReleaseOrderStatus.DOCUMENTS_ISSUED, order.status());
    }

    @Test
    void externalReceipt_isTheOnlyPathToReleaseRegistered_inv3() {
        TitleRetention r = retention();
        ReleaseOrder order = issuedOrder();
        order.submitExternal("REG-2026-0001");
        order.confirmRegistration(ReceiptAuthority.EXTERNAL_REGISTRY);
        // 唯一入口：订单 REGISTRATION_CONFIRMED + EXTERNAL_REGISTRY
        r.markReleaseRegistered(order.status(), ReceiptAuthority.EXTERNAL_REGISTRY);
        assertEquals(TitleRetentionStatus.RELEASE_REGISTERED, r.status());
        r.close();
        assertEquals(TitleRetentionStatus.CLOSED, r.status());
        assertTrue(r.status().isTerminal());
    }

    @Test
    void retentionStatusTerminalSet_b7_i7() {
        TitleRetention r = retention();
        r.markReleaseRegistered(ReleaseOrderStatus.REGISTRATION_CONFIRMED, ReceiptAuthority.EXTERNAL_REGISTRY);
        assertEquals(TitleRetentionStatus.RELEASE_REGISTERED, r.status());
        r.close();
        assertEquals(TitleRetentionStatus.CLOSED, r.status());
        // 终态不可再迁移（I-7）
        assertThrows(Exception.class, r::close);
    }

    @Test
    void contractualOnly_closesWithoutExternalRegistry_c04() {
        TitleRetention r = TitleRetention.register(322L, "VIN-2", true);
        assertEquals(TitleRetentionStatus.CONTRACTUAL_ONLY, r.status());
        r.close();
        assertEquals(TitleRetentionStatus.CLOSED, r.status());
    }
}
