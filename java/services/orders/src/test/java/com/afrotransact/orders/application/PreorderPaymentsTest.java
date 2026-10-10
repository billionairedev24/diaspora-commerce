package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PreorderPaymentsTest {

    @Test
    void allowedMethodsBecomeStripeTypes() {
        assertThat(PreorderPayments.stripeTypes(PreorderPayments.allowed(List.of()))).isEmpty();
        assertThat(PreorderPayments.stripeTypes(PreorderPayments.allowed(List.of(Set.of("CARD", "WALLET", "ACH")))))
                .as("everything allowed: automatic payment methods").isEmpty();
        assertThat(PreorderPayments.stripeTypes(PreorderPayments.allowed(List.of(Set.of("card")))))
                .containsExactly("card");
        assertThat(PreorderPayments.stripeTypes(PreorderPayments.allowed(List.of(Set.of("WALLET", "ACH")))))
                .containsExactly("card", "link");
        assertThat(PreorderPayments.allowed(List.of(Set.of("CARD", "ACH"), Set.of("ACH", "WALLET"))))
                .containsExactly("ACH");
        assertThat(PreorderPayments.stripeTypes(Set.of("ACH"))).as("manual capture: no us_bank_account").isEmpty();
    }

    @Test
    void deliveryFeesAreSpreadByItemsWithTheLargestRemainder() {
        assertThat(CheckoutActivitiesImpl.spread(799, new long[] {2000, 500})).containsExactly(639, 160);
        assertThat(CheckoutActivitiesImpl.spread(799, new long[] {1, 1, 1})).containsExactly(267, 266, 266);
        assertThat(CheckoutActivitiesImpl.spread(799, new long[] {0, 0})).containsExactly(799, 0);
        assertThat(CheckoutActivitiesImpl.spread(0, new long[] {10})).containsExactly(0);
    }
}
