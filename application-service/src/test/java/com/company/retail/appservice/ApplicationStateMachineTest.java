package com.company.retail.appservice;

import com.company.retail.appservice.domain.Application;
import com.company.retail.appservice.domain.ApplicationStatus;
import com.company.retail.appservice.domain.Application.Item;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 申请状态机穷举（R04 §3.1）：合法迁移、非法迁移、终态互斥、提交守卫。
 */
class ApplicationStateMachineTest {

    private Item vehicle() {
        return new Item("VIN00000000000001", "Corolla", 2000000L, 50000L);
    }

    @Test
    void 提交守卫_必填齐_交易形态必填_同意必填() {
        Application ok = Application.submit(100L, 5L, "INSTALLMENT_CREDIT", "PORTAL", 9L, vehicle(), Instant.now());
        assertEquals(ApplicationStatus.SUBMITTED, ok.getStatus());
        // C-01 双形态：交易形态缺失 → RF_DMS_003
        assertThrows(Exception.class, () -> Application.submit(100L, 5L, null, "PORTAL", 9L, vehicle(), Instant.now()));
        // G1：同意缺失 → RF_UW_003
        assertThrows(Exception.class, () -> Application.submit(100L, 5L, "INSTALLMENT_CREDIT", "PORTAL", null, vehicle(), Instant.now()));
        // 车辆必填
        assertThrows(Exception.class, () -> Application.submit(100L, 5L, "INSTALLMENT_CREDIT", "PORTAL", 9L, null, Instant.now()));
    }

    @Test
    void 主链路_提交到补件到审查到批准到签约() {
        Application a = Application.submit(100L, 5L, "MONEY_LENDING", "DMS", 9L, vehicle(), Instant.now());
        a.requestDocuments();
        assertEquals(ApplicationStatus.DOCUMENTS_PENDING, a.getStatus());
        a.transitionTo(ApplicationStatus.VERIFIED);
        a.transitionTo(ApplicationStatus.UNDER_REVIEW);
        a.transitionTo(ApplicationStatus.APPROVED);
        a.transitionTo(ApplicationStatus.CONTRACTING);
        a.transitionTo(ApplicationStatus.CONTRACTED);
        assertTrue(a.isTerminal());
        // 终态不可再迁移（I-7）
        assertThrows(Exception.class, () -> a.transitionTo(ApplicationStatus.CANCELLED));
    }

    @Test
    void 撤回_仅提交后可撤_终态不可撤() {
        Application a = Application.submit(100L, 5L, "INSTALLMENT_CREDIT", "PORTAL", 9L, vehicle(), Instant.now());
        a.withdraw();
        assertEquals(ApplicationStatus.WITHDRAWN, a.getStatus());
        assertTrue(a.isTerminal());
        // 终态不可再迁移
        assertThrows(Exception.class, () -> a.transitionTo(ApplicationStatus.DOCUMENTS_PENDING));
        // DRAFT 不能直接撤（R04 §3.1：SUBMITTED 才有 WITHDRAWN 后继）
        assertThrows(Exception.class,
                () -> ApplicationStatus.DRAFT.assertTransitionTo(ApplicationStatus.WITHDRAWN));
    }

    @Test
    void 非法迁移_跳步被拒() {
        // SUBMITTED 不得直达 VERIFIED（必须过 DOCUMENTS_PENDING）
        assertThrows(Exception.class,
                () -> ApplicationStatus.SUBMITTED.assertTransitionTo(ApplicationStatus.VERIFIED));
        // UNDER_REVIEW 不得直达 CONTRACTING
        assertThrows(Exception.class,
                () -> ApplicationStatus.UNDER_REVIEW.assertTransitionTo(ApplicationStatus.CONTRACTING));
        // REFERRED 只能去 APPROVED/DECLINED
        assertThrows(Exception.class,
                () -> ApplicationStatus.REFERRED.assertTransitionTo(ApplicationStatus.CONTRACTING));
    }

    @Test
    void 终态集合_五个互斥终态() {
        assertEquals(5, ApplicationStatus.TERMINAL.size());
        assertTrue(ApplicationStatus.TERMINAL.contains(ApplicationStatus.DECLINED));
        assertTrue(ApplicationStatus.TERMINAL.contains(ApplicationStatus.WITHDRAWN));
        assertTrue(ApplicationStatus.TERMINAL.contains(ApplicationStatus.CANCELLED));
        assertTrue(ApplicationStatus.TERMINAL.contains(ApplicationStatus.EXPIRED));
        assertTrue(ApplicationStatus.TERMINAL.contains(ApplicationStatus.CONTRACTED));
    }
}
