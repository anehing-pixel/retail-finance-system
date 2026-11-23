package com.company.retail.core;

import com.company.retail.core.domain.PlanTerms;
import com.company.retail.core.domain.ProductProfile;
import com.company.retail.core.domain.ProductTermsResolver;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 还款参数合成策略单测：优先级 = 授信覆盖 &gt; 产品档位默认。
 */
class ProductTermsResolverTest {

    private static ProductProfile profile(int termCount, int rateBp, Integer bonusMonth) {
        return new ProductProfile("AUTO_LOAN_36_0", 1, "汽车贷款 36 期 0 息", "MONEY_LENDING",
                termCount, rateBp, bonusMonth, null, LocalDate.of(2026, 1, 1), null, true, 0);
    }

    @Test
    void noOverride_usesProductDefaults() {
        PlanTerms terms = ProductTermsResolver.resolve(profile(36, 200, 7), null, null, null);
        assertEquals(36, terms.termCount());
        assertEquals(200, terms.rateBp());
        assertEquals(7, terms.bonusMonth());
        assertEquals("AUTO_LOAN_36_0", terms.productCode());
        assertEquals(1, terms.productVersion());
    }

    @Test
    void override_winsOverProductDefaults() {
        PlanTerms terms = ProductTermsResolver.resolve(profile(36, 200, 7), 24, 100, 1);
        assertEquals(24, terms.termCount());
        assertEquals(100, terms.rateBp());
        assertEquals(1, terms.bonusMonth());
        assertEquals("AUTO_LOAN_36_0", terms.productCode(), "档位码仍要落快照");
    }

    @Test
    void partialOverride_keepsOtherDefaults() {
        PlanTerms terms = ProductTermsResolver.resolve(profile(36, 200, 7), null, 50, null);
        assertEquals(36, terms.termCount());
        assertEquals(50, terms.rateBp());
        assertEquals(7, terms.bonusMonth());
    }

    @Test
    void zeroRateProduct_supported() {
        PlanTerms terms = ProductTermsResolver.resolve(profile(36, 0, null), null, null, null);
        assertEquals(0, terms.rateBp());
        assertEquals(36, terms.termCount());
    }

    @Test
    void invalidOverrides_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ProductTermsResolver.resolve(profile(36, 200, null), 0, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> ProductTermsResolver.resolve(profile(36, 200, null), null, -1, null));
        assertThrows(IllegalArgumentException.class,
                () -> ProductTermsResolver.resolve(profile(36, 200, null), null, null, 13));
        assertThrows(IllegalArgumentException.class,
                () -> ProductTermsResolver.resolve(null, null, null, null));
    }

    @Test
    void directInputMode_hasNoProductSnapshot_butKeepsRate() {
        PlanTerms terms = ProductTermsResolver.fromDirectInput(12, 0, null);
        assertNull(terms.productCode());
        assertNull(terms.productVersion());
        assertEquals(12, terms.termCount());
        assertEquals(0, terms.rateBp());
    }

    @Test
    void directInputMode_invalid_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ProductTermsResolver.fromDirectInput(null, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> ProductTermsResolver.fromDirectInput(12, -5, null));
    }
}
