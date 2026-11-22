package com.company.retail.titlerelease;

import com.company.retail.titlerelease.domain.EvidenceChainPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据链八条闭合校验（R05 §6；八条全满足才可进第 7 步）。
 */
class EvidenceChainPolicyTest {

    private static EvidenceChainPolicy.EvidenceSnapshot full() {
        return new EvidenceChainPolicy.EvidenceSnapshot(
                true, true, true, 4, true, true, true, true);
    }

    @Test
    void eightItems_inOrder() {
        List<EvidenceChainPolicy.EvidenceItem> items = EvidenceChainPolicy.evaluate(full());
        assertEquals(8, items.size());
        for (int i = 0; i < 8; i++) {
            assertEquals(i + 1, items.get(i).no());
        }
        assertTrue(EvidenceChainPolicy.allClosed(items));
    }

    @Test
    void noExternalReceipt_items7And8Fail_step7Blocked() {
        // 无外部回执：⑦⑧不闭合 → 不得进第 7 步（TITLE_RELEASE_REGISTERED 不发）
        EvidenceChainPolicy.EvidenceSnapshot s = new EvidenceChainPolicy.EvidenceSnapshot(
                true, true, true, 4, true, true, false, false);
        List<EvidenceChainPolicy.EvidenceItem> items = EvidenceChainPolicy.evaluate(s);
        assertFalse(items.get(6).satisfied());
        assertFalse(items.get(7).satisfied());
        assertFalse(EvidenceChainPolicy.allClosed(items));
    }

    @Test
    void documentsIncomplete_item4And5Fail() {
        EvidenceChainPolicy.EvidenceSnapshot s = new EvidenceChainPolicy.EvidenceSnapshot(
                true, true, true, 2, false, true, true, true);
        List<EvidenceChainPolicy.EvidenceItem> items = EvidenceChainPolicy.evaluate(s);
        assertFalse(items.get(3).satisfied());
        assertFalse(items.get(4).satisfied());
        assertFalse(EvidenceChainPolicy.allClosed(items));
    }

    @Test
    void loanNotTerminal_item1Fails_inv3() {
        EvidenceChainPolicy.EvidenceSnapshot s = new EvidenceChainPolicy.EvidenceSnapshot(
                false, false, true, 4, true, true, true, true);
        List<EvidenceChainPolicy.EvidenceItem> items = EvidenceChainPolicy.evaluate(s);
        assertFalse(items.get(0).satisfied());
        assertFalse(items.get(1).satisfied());
        assertFalse(EvidenceChainPolicy.allClosed(items));
    }
}
