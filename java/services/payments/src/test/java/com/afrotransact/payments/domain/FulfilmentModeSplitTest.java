package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Who earns the delivery charge, per fulfilment mode (docs/engineering/delivery-fulfilment.md "Money"):
 * Fulfilled by AfroTransact (the seller's store collected by AfroTransact, or the fulfilment centre) is AfroTransact
 * revenue and never part of the seller's payable; a seller who ships keeps what the customer paid for shipping; pickup
 * has no delivery charge for anyone.
 */
class FulfilmentModeSplitTest {

    private final PaymentSplitter splitter = new DefaultPaymentSplitter(Fixtures.RETAIL);
    private final FeeSchedule schedule = Fixtures.scheduleWithDelivery(Fixtures.launchDelivery(List.of()));

    private PaymentSplit split(OrderLine... lines) {
        return splitter.split(List.of(lines), schedule, Money.zero(Fixtures.USD));
    }

    private static RecipientShare share(PaymentSplit split, String id) {
        return split.recipients().stream().filter(r -> r.recipientId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void fulfilledByAfroTransactFromTheSellersStoreDeliveryChargeIsPlatformRevenue() {
        PaymentSplit s = split(Fixtures.deliveryLine("D1", "org_c", "Groceries", 2, 3000, 599, 248, 200));

        assertThat(share(s, "org_c").shipping().minor()).isZero();
        assertThat(s.platform().shipping().minor()).isEqualTo(599);
        // The seller is paid items less commission and nothing else (no pickup fee, owner decision); none of the 599 reaches them.
        assertThat(share(s, "org_c").net().minor()).isEqualTo(3000 - 240);
        assertThat(s.line("D1").orElseThrow().shippingToPlatform()).isTrue();
    }

    @Test
    void fulfilledByAfroTransactFromTheFulfilmentCentreDeliveryChargeIsPlatformRevenue() {
        PaymentSplit s = split(Fixtures.line("F1", "org_a", "Groceries", Fulfilment.FBA, 1, 2000, 599, 165));

        assertThat(share(s, "org_a").shipping().minor()).isZero();
        assertThat(s.platform().shipping().minor()).isEqualTo(599);
        assertThat(share(s, "org_a").net().minor()).isLessThan(2000);
        assertThat(s.line("F1").orElseThrow().shippingToPlatform()).isTrue();
    }

    @Test
    void aSellerWhoShipsKeepsTheShippingTheCustomerPaid() {
        PaymentSplit s = split(Fixtures.line("S1", "org_a", "Groceries", Fulfilment.SELF, 1, 2000, 599, 165));

        assertThat(share(s, "org_a").shipping().minor()).isEqualTo(599);
        assertThat(s.platform().shipping().minor()).isZero();
        assertThat(s.line("S1").orElseThrow().shippingToPlatform()).isFalse();
    }

    @Test
    void pickupHasNoDeliveryChargeForAnyone() {
        PaymentSplit s = split(Fixtures.line("P1", "org_a", "Groceries", Fulfilment.SELF, 1, 2000, 0, 165));

        assertThat(share(s, "org_a").shipping().minor()).isZero();
        assertThat(s.platform().shipping().minor()).isZero();
        assertThat(share(s, "org_a").net().minor()).isLessThan(2000).isGreaterThan(1500);
    }

    @Test
    void oneOrderWithAllThreeModesSplitsEachItsOwnWay() {
        PaymentSplit s = split(
                Fixtures.deliveryLine("D1", "org_c", "Groceries", 1, 1000, 599, 82, 200),
                Fixtures.line("S1", "org_s", "Groceries", Fulfilment.SELF, 1, 1000, 700, 82),
                Fixtures.line("P1", "org_p", "Groceries", Fulfilment.SELF, 1, 1000, 0, 82));

        assertThat(s.platform().shipping().minor()).isEqualTo(599);
        assertThat(share(s, "org_s").shipping().minor()).isEqualTo(700);
        assertThat(share(s, "org_c").shipping().minor()).isZero();
        assertThat(share(s, "org_p").shipping().minor()).isZero();
        // Platform revenue includes the 599 it earns delivering, and nothing of the other two sellers' shipping.
        assertThat(s.platform().net().minor()).isGreaterThanOrEqualTo(599);
    }
}
