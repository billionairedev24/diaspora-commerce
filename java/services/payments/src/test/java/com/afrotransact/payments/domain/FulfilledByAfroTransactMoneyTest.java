package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Owner decision, pinned: Fulfilled by AfroTransact (FBA) is free for the seller: AfroTransact collects from the seller and
 * delivers; there is no per-unit FBA fee and no seller delivery fee (retired, never applied). The delivery fee is paid by
 * the customer and is 100 % AfroTransact revenue, never part of the seller payable; the seller's pickup fee and the
 * per-unit FBA fee are 0 unless staff publish a fee schedule that sets them; Seller pickup has no delivery fee for
 * anybody. Marketplace commission is the same in every mode. The delivery fee's tax is untouched: tax is the line's own
 * and never moves with the fee.
 */
class FulfilledByAfroTransactMoneyTest {

    private static final Instant FROM = Instant.parse("2020-01-01T00:00:00Z");

    private final PaymentSplitter splitter = new DefaultPaymentSplitter(Fixtures.RETAIL);

    /** The schedule of a fresh install after V24: FBA per unit 0, seller pickup fee flat 0; commission as before. */
    private static FeeSchedule freeForSellers() {
        DeliveryPricing delivery = new DeliveryPricing(new DeliveryTariff(DeliveryMode.DISTANCE, Fixtures.usd(0),
                Fixtures.usd(499), 85, List.of(), Fixtures.usd(599), Fixtures.usd(2499)),
                DeliveryTariff.flat(Fixtures.usd(0)), new BigDecimal("25"), List.of());
        return new FeeSchedule(UUID.randomUUID(), 2, FROM, Fixtures.USD,
                Map.of("Groceries", 800, "Fashion", 1200, "Services", 1000), 1000, List.of(), 290, Fixtures.usd(30),
                Fixtures.usd(0), true, null, "system:migration", FROM, null, delivery);
    }

    private PaymentSplit split(FeeSchedule schedule, OrderLine... lines) {
        return splitter.split(List.of(lines), schedule, Money.zero(Fixtures.USD));
    }

    private static RecipientShare share(PaymentSplit split, String id) {
        return split.recipients().stream().filter(r -> r.recipientId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void fbaFromTheFulfilmentCentreCostsTheSellerNothingAndTheCustomerPaysTheDeliveryFee() {
        PaymentSplit s = split(freeForSellers(),
                Fixtures.line("F1", "org_a", "Groceries", Fulfilment.FBA, 3, 3000, 599, 248));

        RecipientShare seller = share(s, "org_a");
        assertThat(seller.fbaFees().minor()).isZero();
        assertThat(seller.shipping().minor()).isZero();
        assertThat(seller.net().minor()).as("items less commission only").isEqualTo(3000 - 240);
        assertThat(s.platform().shipping().minor()).as("the customer's delivery fee is AfroTransact's").isEqualTo(599);
        assertThat(s.line("F1").orElseThrow().shippingToPlatform()).isTrue();
        assertThat(s.line("F1").orElseThrow().fbaFee().minor()).isZero();
    }

    @Test
    void fbaFromTheSellersStoreHasNoPickupFeeForTheSellerAndTheDeliveryFeeIsPlatformRevenue() {
        PaymentSplit s = split(freeForSellers(), Fixtures.deliveryLine("D1", "org_c", "Groceries", 2, 3000, 599, 248, 0));

        RecipientShare seller = share(s, "org_c");
        assertThat(seller.deliveryFees().minor()).as("no pickup fee charged to the seller").isZero();
        assertThat(seller.shipping().minor()).isZero();
        assertThat(seller.net().minor()).isEqualTo(3000 - 240);
        assertThat(s.platform().shipping().minor()).isEqualTo(599);
        assertThat(s.platform().net().minor()).as("platform keeps exactly the fee and the commission")
                .isEqualTo(599 + 240);
    }

    @Test
    void theSellersPayableIsTheSameWithOrWithoutTheDeliveryFee() {
        PaymentSplit free = split(freeForSellers(), Fixtures.deliveryLine("D1", "org_c", "Groceries", 1, 3000, 0, 248, 0));
        PaymentSplit delivered = split(freeForSellers(),
                Fixtures.deliveryLine("D1", "org_c", "Groceries", 1, 3000, 1299, 248, 0));

        assertThat(share(delivered, "org_c").net()).isEqualTo(share(free, "org_c").net());
        assertThat(share(delivered, "org_c").tax()).as("tax never moves with the fee")
                .isEqualTo(share(free, "org_c").tax());
        assertThat(delivered.platform().shipping().minor() - free.platform().shipping().minor()).isEqualTo(1299);
    }

    @Test
    void aStaffScheduleCannotChargeTheSellerAPerUnitFbaFee() {
        FeeSchedule staffSet = new FeeSchedule(UUID.randomUUID(), 3, FROM, Fixtures.USD,
                Map.of("Groceries", 800), 1000, List.of(), 290, Fixtures.usd(30), Fixtures.usd(100), true, null,
                "staff-1", FROM);
        PaymentSplit s = split(staffSet, Fixtures.line("F1", "org_a", "Groceries", Fulfilment.FBA, 3, 3000, 599, 248));

        // The per-unit FBA fee is retired (AfroTransact collects from the seller and the customer pays delivery): a schedule that
        // still carries one, here $1.00 per unit, is never applied to a new payment.
        assertThat(share(s, "org_a").fbaFees().minor()).isZero();
        assertThat(share(s, "org_a").net().minor()).isEqualTo(3000 - 240);
        assertThat(s.platform().shipping().minor()).isEqualTo(599);
    }

    @Test
    void sellerPickupHasNoDeliveryFeeForAnyoneAndCommissionIsUnchanged() {
        PaymentSplit s = split(freeForSellers(), Fixtures.line("P1", "org_p", "Groceries", Fulfilment.SELF, 1, 2000, 0, 165));

        assertThat(share(s, "org_p").shipping().minor()).isZero();
        assertThat(s.platform().shipping().minor()).isZero();
        assertThat(share(s, "org_p").commission().minor()).isEqualTo(160);
        assertThat(share(s, "org_p").net().minor()).isEqualTo(2000 - 160);
    }

    @Test
    void commissionIsTheSameInEveryMode() {
        PaymentSplit s = split(freeForSellers(),
                Fixtures.line("F1", "org_a", "Groceries", Fulfilment.FBA, 1, 1000, 599, 82),
                Fixtures.deliveryLine("D1", "org_c", "Groceries", 1, 1000, 599, 82, 0),
                Fixtures.line("P1", "org_p", "Groceries", Fulfilment.SELF, 1, 1000, 0, 82));

        for (String org : List.of("org_a", "org_c", "org_p")) {
            assertThat(share(s, org).commission().minor()).as(org).isEqualTo(80);
        }
    }

    /**
     * Seller self-shipping (behind the staff switch, off by default): the seller's flat shipping charge is the SELLER's revenue.
     * Commission is on the items only (the existing rule: no commission on shipping), AfroTransact books none of it, and the
     * payable is items plus shipping less commission.
     */
    @Test
    void aSellersOwnShippingChargeIsTheSellersRevenueWithNoCommissionOnIt() {
        PaymentSplit s = split(freeForSellers(),
                Fixtures.line("S1", "org_a", "Groceries", Fulfilment.SELF, 2, 3000, 750, 248));

        RecipientShare seller = share(s, "org_a");
        assertThat(seller.shipping().minor()).as("the seller keeps the shipping the customer paid").isEqualTo(750);
        assertThat(seller.commission().minor()).as("commission is on items only (8 percent of 3000)").isEqualTo(240);
        assertThat(seller.net().minor()).isEqualTo(3000 + 750 - 240);
        assertThat(s.platform().shipping().minor()).as("AfroTransact books none of it").isZero();
        assertThat(s.platform().net().minor()).isEqualTo(240);
        assertThat(s.line("S1").orElseThrow().shippingToPlatform()).isFalse();
    }
}
