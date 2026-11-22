package com.company.retail.core;

import com.company.platform.exception.BusinessException;
import com.company.retail.core.integration.DisbursementGateChecker;
import com.company.retail.domain.exception.RFErrorCode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 代付前门禁 fail-closed 语义（G8 / R09 §7.1；挡板 GateClient，不起真依赖进程）。
 */
class DisbursementGateFailClosedTest {

    private static DisbursementGateChecker.GateAnswer ok(boolean allowed, String reasonCode) {
        Map<String, Object> data = new HashMap<>();
        data.put("allowed", allowed);
        data.put("reasonCode", reasonCode);
        return new DisbursementGateChecker.GateAnswer("OK", data);
    }

    private static DisbursementGateChecker.GateAnswer notOk() {
        return new DisbursementGateChecker.GateAnswer("RF_VALID_001", Map.of());
    }

    @Test
    void allGatesExplicitlyAllowed_proceeds() {
        DisbursementGateChecker checker = new DisbursementGateChecker(
                id -> ok(true, "CONTRACT_EFFECTIVE"),
                id -> ok(true, "UNDERWRITING_APPROVED"));
        checker.checkAll(1L, 2L); // 不抛即通过
    }

    @Test
    void contractNotEffective_isRejected() {
        DisbursementGateChecker checker = new DisbursementGateChecker(
                id -> ok(false, "CONTRACT_NOT_EFFECTIVE"),
                id -> ok(true, "UNDERWRITING_APPROVED"));
        BusinessException ex = assertThrows(BusinessException.class, () -> checker.checkAll(1L, 2L));
        assertEquals(RFErrorCode.RF_LOAN_001, ex.getErrorCode());
        assertEquals("CONTRACT_NOT_EFFECTIVE", ex.getDetails().get("reasonCode"));
    }

    @Test
    void underwritingNotApproved_isRejected_withReasonCodePassthrough() {
        DisbursementGateChecker checker = new DisbursementGateChecker(
                id -> ok(true, "CONTRACT_EFFECTIVE"),
                id -> ok(false, "UNDERWRITING_NOT_APPROVED"));
        BusinessException ex = assertThrows(BusinessException.class, () -> checker.checkAll(1L, 2L));
        assertEquals(RFErrorCode.RF_LOAN_001, ex.getErrorCode());
        assertEquals("UNDERWRITING_NOT_APPROVED", ex.getDetails().get("reasonCode"));
    }

    @Test
    void gateTimeoutOrConnectionFailure_isRejected_failClosed() {
        // Feign 超时/连接失败/非 2xx 都以异常形态出现：UNKNOWN 不视为通过（R09 §7.1）
        DisbursementGateChecker checker = new DisbursementGateChecker(
                id -> {
                    throw new IllegalStateException("connect timed out");
                },
                id -> ok(true, "UNDERWRITING_APPROVED"));
        BusinessException ex = assertThrows(BusinessException.class, () -> checker.checkAll(1L, 2L));
        assertEquals(RFErrorCode.RF_LOAN_001, ex.getErrorCode());
        assertEquals("CONTRACT_GATE_UNREACHABLE", ex.getDetails().get("reasonCode"));
    }

    @Test
    void nonOkBusinessCode_isRejected() {
        DisbursementGateChecker checker = new DisbursementGateChecker(
                id -> notOk(),
                id -> ok(true, "UNDERWRITING_APPROVED"));
        BusinessException ex = assertThrows(BusinessException.class, () -> checker.checkAll(1L, 2L));
        assertEquals(RFErrorCode.RF_LOAN_001, ex.getErrorCode());
        assertEquals("CONTRACT_GATE_NOT_OK", ex.getDetails().get("reasonCode"));
    }

    @Test
    void missingDataOrAllowedField_isRejected_defaultDeny() {
        // data 缺 allowed 键：默认拒绝（不是默认放行）
        DisbursementGateChecker checker = new DisbursementGateChecker(
                id -> new DisbursementGateChecker.GateAnswer("OK", Map.of()),
                id -> ok(true, "UNDERWRITING_APPROVED"));
        BusinessException ex = assertThrows(BusinessException.class, () -> checker.checkAll(1L, 2L));
        assertEquals("CONTRACT_GATE_DENIED", ex.getDetails().get("reasonCode"));
    }

    @Test
    void contractGateCheckedBeforeUnderwritingGate() {
        // 顺序断言：合同门禁在前；合同被拒时审查门禁不应被调用（fail-fast）
        AtomicInteger underwritingCalls = new AtomicInteger();
        DisbursementGateChecker checker = new DisbursementGateChecker(
                id -> ok(false, "CONTRACT_NOT_EFFECTIVE"),
                id -> {
                    underwritingCalls.incrementAndGet();
                    return ok(true, "UNDERWRITING_APPROVED");
                });
        assertThrows(BusinessException.class, () -> checker.checkAll(1L, 2L));
        assertEquals(0, underwritingCalls.get());
    }
}
