package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** S298: changing only how the plan is paid on the Plan step keeps the card already added. */
class PlanSectionTest {

    private static final PlanSection STORED = new PlanSection("seller_growth", Cadence.MONTHLY, PaymentMethodKind.CARD,
            "pm_1", "visa", "4242", 12, 2030, "GROW60", PlanFeeCollection.EARNINGS_FIRST);

    @Test
    void theSamePlanWithoutACardKeepsTheStoredCardAndPromoCode() {
        PlanSection merged = new PlanSection("seller_growth", Cadence.MONTHLY, null, null, null, null, null, null, null,
                PlanFeeCollection.CARD).keepingPaymentOf(STORED);
        assertThat(merged.stripePaymentMethodId()).isEqualTo("pm_1");
        assertThat(merged.cardLast4()).isEqualTo("4242");
        assertThat(merged.promoCode()).isEqualTo("GROW60");
        assertThat(merged.planFeeCollection()).isEqualTo(PlanFeeCollection.CARD);
    }

    @Test
    void anotherPlanCadenceOrCardReplacesIt() {
        assertThat(new PlanSection("seller_pro", Cadence.MONTHLY, null, null, null, null, null, null, null, null)
                .keepingPaymentOf(STORED).stripePaymentMethodId()).isNull();
        assertThat(new PlanSection("seller_growth", Cadence.ANNUAL, null, null, null, null, null, null, null, null)
                .keepingPaymentOf(STORED).stripePaymentMethodId()).isNull();
        assertThat(new PlanSection("seller_growth", Cadence.MONTHLY, PaymentMethodKind.CARD, "pm_2", "visa", "1111", 1,
                2031, null, null).keepingPaymentOf(STORED).stripePaymentMethodId()).isEqualTo("pm_2");
        assertThat(STORED.keepingPaymentOf(null)).isSameAs(STORED);
    }
}
