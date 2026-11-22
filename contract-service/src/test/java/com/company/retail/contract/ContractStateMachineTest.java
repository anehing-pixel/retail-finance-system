package com.company.retail.contract;

import com.company.platform.exception.BusinessException;
import com.company.retail.contract.domain.Contract;
import com.company.retail.contract.domain.ContractPolicy;
import com.company.retail.contract.domain.ContractStatus;
import com.company.retail.contract.domain.StatutoryDocument;
import com.company.retail.contract.domain.TransactionScheme;
import com.company.retail.domain.exception.RFErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合同状态机穷举测试（R04 §3.3 / U-4，领域层零 Spring）。
 */
class ContractStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-11-22T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Tokyo"));

    private static ContractPolicy policy(boolean coolingOff) {
        return new ContractPolicy(Set.of(StatutoryDocument.PRE_CONTRACT_STATEMENT,
                StatutoryDocument.CONTRACT_DOCUMENT), coolingOff, 8);
    }

    private static StatutoryDocument delivered(String docType) {
        return new StatutoryDocument(null, 1L, docType, "v1", NOW, "ELECTRONIC", null, null);
    }

    private static Contract deliveredContract(TransactionScheme scheme, boolean coolingOff) {
        Contract contract = Contract.draft(100L, 200L, scheme);
        contract.markDocumentsDelivered(policy(coolingOff),
                List.of(delivered(StatutoryDocument.PRE_CONTRACT_STATEMENT),
                        delivered(StatutoryDocument.CONTRACT_DOCUMENT)), CLOCK);
        return contract;
    }

    // ---- 正常流转 ----

    @Test
    void draft_startsAtDraftStatus() {
        Contract contract = Contract.draft(1L, 2L, TransactionScheme.INSTALLMENT_CREDIT);
        assertEquals(ContractStatus.DRAFT, contract.status());
    }

    @Test
    void delivered_advanceFromDraftWhenAllRequiredDocsDelivered() {
        Contract contract = deliveredContract(TransactionScheme.INSTALLMENT_CREDIT, true);
        assertEquals(ContractStatus.DOCUMENTS_DELIVERED, contract.status());
    }

    @Test
    void sign_withCoolingOff_goesToCoolingOffWithEightDaysPlaceholder() {
        Contract contract = deliveredContract(TransactionScheme.INSTALLMENT_CREDIT, true);
        boolean coolingOff = contract.sign(policy(true), List.of(
                delivered(StatutoryDocument.PRE_CONTRACT_STATEMENT),
                delivered(StatutoryDocument.CONTRACT_DOCUMENT)), CLOCK, NOW);
        // C-08：冷静期 8 日占位（个品分期）
        assertTrue(coolingOff);
        assertEquals(ContractStatus.COOLING_OFF, contract.status());
        assertEquals(LocalDate.ofInstant(NOW, Contract.JST).plusDays(8), contract.coolingOffUntil());
        assertEquals(NOW, contract.signedAt());
    }

    @Test
    void sign_coolingOffDisabled_goesStraightToEffective() {
        Contract contract = deliveredContract(TransactionScheme.INSTALLMENT_CREDIT, false);
        boolean coolingOff = contract.sign(policy(false), List.of(
                delivered(StatutoryDocument.PRE_CONTRACT_STATEMENT),
                delivered(StatutoryDocument.CONTRACT_DOCUMENT)), CLOCK, NOW);
        assertFalse(coolingOff);
        assertEquals(ContractStatus.EFFECTIVE, contract.status());
        assertEquals(NOW, contract.effectiveAt());
        assertTrue(contract.isEffective());
    }

    @Test
    void moneyLending_neverEntersCoolingOff_evenWhenEnabled() {
        // C-08 冷静期是个品分期下的解约权，汽车贷款不适用
        Contract contract = deliveredContract(TransactionScheme.MONEY_LENDING, true);
        boolean coolingOff = contract.sign(policy(true), List.of(
                delivered(StatutoryDocument.PRE_CONTRACT_STATEMENT),
                delivered(StatutoryDocument.CONTRACT_DOCUMENT)), CLOCK, NOW);
        assertFalse(coolingOff);
        assertEquals(ContractStatus.EFFECTIVE, contract.status());
    }

    @Test
    void coolingOff_confirmExpires_thenEffective() {
        Contract contract = deliveredContract(TransactionScheme.INSTALLMENT_CREDIT, true);
        contract.sign(policy(true), List.of(
                delivered(StatutoryDocument.PRE_CONTRACT_STATEMENT),
                delivered(StatutoryDocument.CONTRACT_DOCUMENT)), CLOCK, NOW);
        contract.confirmCoolingOffExpired(CLOCK);
        assertEquals(ContractStatus.EFFECTIVE, contract.status());
        assertTrue(contract.isEffective());
    }

    @Test
    void coolingOff_cancelDuringCoolingOff_isTerminal() {
        Contract contract = deliveredContract(TransactionScheme.INSTALLMENT_CREDIT, true);
        contract.sign(policy(true), List.of(
                delivered(StatutoryDocument.PRE_CONTRACT_STATEMENT),
                delivered(StatutoryDocument.CONTRACT_DOCUMENT)), CLOCK, NOW);
        contract.cancel();
        assertEquals(ContractStatus.CANCELLED, contract.status());
        assertTrue(contract.status().isTerminal());
    }

    // ---- 守卫（G7 / RF_CTR_001） ----

    @Test
    void sign_withoutDelivery_isHardBlockedByRfCtr001() {
        // 防御纵深：即便状态已到 DOCUMENTS_DELIVERED，签订时交付清单为空仍被 G7 硬阻断
        Contract contract = deliveredContract(TransactionScheme.INSTALLMENT_CREDIT, true);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> contract.sign(policy(true), List.of(), CLOCK, NOW));
        assertEquals(RFErrorCode.RF_CTR_001, ex.getErrorCode());
    }

    @Test
    void sign_fromDraftState_isRejectedByStateMachine() {
        // 未走 DOCUMENTS_DELIVERED 直接签订：状态机拒绝（R04 §3.3 不允许 DRAFT → SIGNED）
        Contract contract = Contract.draft(100L, 200L, TransactionScheme.INSTALLMENT_CREDIT);
        List<StatutoryDocument> docs = List.of(
                delivered(StatutoryDocument.PRE_CONTRACT_STATEMENT),
                delivered(StatutoryDocument.CONTRACT_DOCUMENT));
        assertThrows(IllegalStateException.class, () -> contract.sign(policy(true), docs, CLOCK, NOW));
    }

    @Test
    void markDocumentsDelivered_withMissingDocType_reportsMissingTypes() {
        Contract contract = Contract.draft(100L, 200L, TransactionScheme.INSTALLMENT_CREDIT);
        BusinessException ex = assertThrows(BusinessException.class, () -> contract.markDocumentsDelivered(
                policy(true), List.of(delivered(StatutoryDocument.PRE_CONTRACT_STATEMENT)), CLOCK));
        assertEquals(RFErrorCode.RF_CTR_001, ex.getErrorCode());
        assertTrue(String.valueOf(ex.getDetails().get("missingDocTypes"))
                .contains(StatutoryDocument.CONTRACT_DOCUMENT));
    }

    // ---- 终态与非法迁移（I-7） ----

    @Test
    void terminalStates_cannotTransitionAnywhere() {
        // DOCUMENTS_DELIVERED → CANCELLED（解约），随后终态不可再迁移（I-7）
        Contract cancelled = deliveredContract(TransactionScheme.INSTALLMENT_CREDIT, false);
        cancelled.cancel();
        List<StatutoryDocument> docs = List.of(
                delivered(StatutoryDocument.PRE_CONTRACT_STATEMENT),
                delivered(StatutoryDocument.CONTRACT_DOCUMENT));
        assertThrows(IllegalStateException.class, () -> cancelled.sign(policy(false), docs, CLOCK, NOW));
        assertThrows(IllegalStateException.class, cancelled::cancel);
        assertThrows(IllegalStateException.class, () -> cancelled.confirmCoolingOffExpired(CLOCK));
        assertThrows(IllegalStateException.class, () -> cancelled.markDocumentsDelivered(
                policy(false), docs, CLOCK));
    }

    @Test
    void effective_onlyMovesToTerminated() {
        Contract contract = deliveredContract(TransactionScheme.INSTALLMENT_CREDIT, false);
        contract.sign(policy(false), List.of(
                delivered(StatutoryDocument.PRE_CONTRACT_STATEMENT),
                delivered(StatutoryDocument.CONTRACT_DOCUMENT)), CLOCK, NOW);
        contract.terminate();
        assertEquals(ContractStatus.TERMINATED, contract.status());
        assertTrue(contract.status().isTerminal());
    }
}
