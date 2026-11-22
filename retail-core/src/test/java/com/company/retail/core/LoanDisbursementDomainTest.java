package com.company.retail.core;

import com.company.platform.exception.BusinessException;
import com.company.retail.core.domain.Loan;
import com.company.retail.core.domain.LoanStatus;
import com.company.retail.domain.exception.RFErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 债权状态机与代付守卫（R04 §3.4 / I-1，领域层零 Spring）。
 */
class LoanDisbursementDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-22T03:00:00Z");

    @Test
    void create_startsPendingDisbursement_balanceIsPrincipalPlusFee() {
        Loan loan = Loan.create(500L, 2_000_000L, 30_000L);
        assertEquals(LoanStatus.PENDING_DISBURSEMENT, loan.status());
        // INV-2 起点：balance = principal + fee（代付前无利息）
        assertEquals(2_030_000L, loan.balance());
    }

    @Test
    void disbursement_happyPath_pendingToDisbursingToActive() {
        Loan loan = Loan.create(500L, 2_000_000L, 0);
        loan.assertDisbursementAllowed();
        loan.startDisbursement();
        assertEquals(LoanStatus.DISBURSING, loan.status());
        loan.completeDisbursement(NOW);
        assertEquals(LoanStatus.ACTIVE, loan.status());
        assertEquals(NOW, loan.disbursedAt());
    }

    @Test
    void doubleDisbursement_isRejectedByDomainGuard_rfLoan002() {
        // I-1：一份合同只允许一次成功代付
        Loan loan = Loan.create(500L, 2_000_000L, 0);
        loan.startDisbursement();
        loan.completeDisbursement(NOW);
        BusinessException ex = assertThrows(BusinessException.class, loan::assertDisbursementAllowed);
        assertEquals(RFErrorCode.RF_LOAN_002, ex.getErrorCode());
    }

    @Test
    void disburseFailed_canReplayOnce_notInfiniteRetry() {
        Loan loan = Loan.create(500L, 2_000_000L, 0);
        loan.startDisbursement();
        loan.markDisburseFailed();
        assertEquals(LoanStatus.DISBURSE_FAILED, loan.status());
        // 失败可重放（DISBURSE_FAILED → DISBURSING）
        loan.assertDisbursementAllowed();
        loan.startDisbursement();
        loan.completeDisbursement(NOW);
        assertEquals(LoanStatus.ACTIVE, loan.status());
        // ACTIVE 后不允许再进 DISBURSING（不自动无限重试）
        assertThrows(IllegalStateException.class, loan::startDisbursement);
    }

    @Test
    void cancel_allowedBeforeDisbursement_blockedAfter() {
        Loan loan = Loan.create(500L, 2_000_000L, 0);
        loan.cancel();
        assertTrue(loan.status().isTerminal());
        Loan active = Loan.create(501L, 2_000_000L, 0);
        active.startDisbursement();
        active.completeDisbursement(NOW);
        assertThrows(IllegalStateException.class, active::cancel);
    }

    @Test
    void negativeAmounts_rejected() {
        assertThrows(IllegalArgumentException.class, () -> Loan.create(500L, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> Loan.create(500L, 100, -1));
    }
}
