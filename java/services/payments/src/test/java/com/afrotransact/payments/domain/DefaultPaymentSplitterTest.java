package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DefaultPaymentSplitterTest {

    private final PaymentSplitter splitter = new DefaultPaymentSplitter(Fixtures.RETAIL);

    /** Two sellers and AfroTransact Retail in one charge (the "Payment split" panel example). */
    @Test
    void workedThreeRecipientExample() {
        List<OrderLine> lines = List.of(
                Fixtures.line("L1", "org_a", "Groceries", Fulfilment.SELF, 2, 2000, 500, 165),
                Fixtures.line("L2", Fixtures.RETAIL, "Groceries", Fulfilment.FBA, 1, 1000, 300, 83),
                Fixtures.line("L3", "org_b", "Fashion", Fulfilment.FBA, 3, 3000, 0, 248));
        FeeSchedule schedule = Fixtures.schedule();
        Money fee = schedule.estimateCardFee(Fixtures.usd(7296));
        assertThat(fee.minor()).isEqualTo(242);

        PaymentSplit split = splitter.split(lines, schedule, fee);

        assertThat(split.charge().minor()).isEqualTo(7296);
        RecipientShare platform = split.platform();
        RecipientShare a = share(split, "org_a");
        RecipientShare retail = share(split, Fixtures.RETAIL);
        RecipientShare b = share(split, "org_b");
        assertThat(a.commission().minor()).isEqualTo(160);
        assertThat(b.commission().minor()).isEqualTo(360);
        assertThat(retail.commission().minor()).isZero();
        assertThat(retail.kind()).isEqualTo(RecipientKind.RETAIL);
        assertThat(retail.fbaFees().minor()).isZero();
        assertThat(retail.shipping().minor()).isEqualTo(300);
        assertThat(b.fbaFees().minor()).isEqualTo(975);
        assertThat(platform.shipping().minor()).isZero();
        // Largest remainder: exact 0 / 88.40 / 45.87 / 107.73 → floors 240, two cents to the largest remainders.
        assertThat(platform.stripeFee().minor()).isZero();
        assertThat(a.stripeFee().minor()).isEqualTo(88);
        assertThat(retail.stripeFee().minor()).isEqualTo(46);
        assertThat(b.stripeFee().minor()).isEqualTo(108);
        assertThat(a.net().minor()).isEqualTo(2252);
        assertThat(retail.net().minor()).isEqualTo(1000 + 300 - 46);
        assertThat(b.net().minor()).isEqualTo(1557);
        assertThat(platform.commission().minor()).isEqualTo(520);
        assertThat(platform.fbaFees().minor()).isEqualTo(975);
        assertThat(platform.net().minor()).isEqualTo(1495);
        assertThat(split.totalTax().minor()).isEqualTo(496);
        assertThat(a.taxByJurisdiction()).containsEntry("US-TX", Fixtures.usd(165));
    }

    /**
     * The README's AfroTransact Delivery example (S255): org_c's groceries collected from its store 7.4 road miles
     * from the customer (customer charge 499 + 85 × 7.4 = 1128, seller fee 200), next to org_a's self-shipped line.
     */
    @Test
    void afroTransactDeliveryWorkedExample() {
        FeeSchedule schedule = Fixtures.scheduleWithDelivery(Fixtures.launchDelivery(List.of()));
        DeliveryQuote quote = schedule.quoteDelivery("org_c", new BigDecimal("7.4"), 1, Fixtures.RETAIL);
        assertThat(quote.customerCharge().minor()).isEqualTo(1128);
        assertThat(quote.sellerFee().minor()).isEqualTo(200);
        List<OrderLine> lines = List.of(
                Fixtures.deliveryLine("D1", "org_c", "Groceries", 2, 3000, 1128, 248, 200),
                Fixtures.line("A1", "org_a", "Groceries", Fulfilment.SELF, 1, 1500, 500, 124));
        Money fee = schedule.estimateCardFee(Fixtures.usd(6500));
        assertThat(fee.minor()).isEqualTo(219);

        PaymentSplit split = splitter.split(lines, schedule, fee);

        RecipientShare platform = split.platform();
        RecipientShare c = share(split, "org_c");
        RecipientShare a = share(split, "org_a");
        assertThat(platform.shipping().minor()).isEqualTo(1128);
        assertThat(platform.deliveryFees().minor()).isEqualTo(200);
        assertThat(c.shipping().minor()).isZero();
        assertThat(c.commission().minor()).isEqualTo(240);
        assertThat(c.fbaFees().minor()).isZero();
        assertThat(c.deliveryFees().minor()).isEqualTo(200);
        // Weights 1128 / 3248 / 2124: exact 38.00 / 109.43 / 71.56 → floors 218, the leftover cent to org_a.
        assertThat(platform.stripeFee().minor()).isEqualTo(38);
        assertThat(c.stripeFee().minor()).isEqualTo(109);
        assertThat(a.stripeFee().minor()).isEqualTo(72);
        assertThat(c.net().minor()).isEqualTo(3000 - 240 - 200 - 109);
        assertThat(a.net().minor()).isEqualTo(1500 + 500 - 120 - 72);
        assertThat(platform.net().minor()).isEqualTo(1128 + 360 + 200 - 38);
        assertThat(split.line("D1").orElseThrow().shippingToPlatform()).isTrue();
        assertThat(split.line("D1").orElseThrow().deliveryFee().minor()).isEqualTo(200);
        assertThat(split.line("A1").orElseThrow().deliveryFee().minor()).isZero();

        Journal capture = Postings.capture(UUID.randomUUID(), Instant.EPOCH, "k", "o1", "p1", "ch", split);
        assertThat(capture.lines()).anySatisfy(l -> {
            assertThat(l.account().chart().code()).isEqualTo("4250");
            assertThat(l.amount().minor()).isEqualTo(200);
            assertThat(l.side()).isEqualTo(EntrySide.CREDIT);
        });
        assertThat(capture.lines()).anySatisfy(l -> {
            assertThat(l.account().chart().code()).isEqualTo("4200");
            assertThat(l.amount().minor()).isEqualTo(1128);
        });
    }

    @Test
    void afroTransactRetailNeverPaysTheSellerDeliveryFee() {
        FeeSchedule schedule = Fixtures.scheduleWithDelivery(Fixtures.launchDelivery(List.of(
                new SellerDeliveryOverride(Fixtures.RETAIL, DeliveryTariff.flat(Fixtures.usd(900))))));
        DeliveryQuote quote = schedule.quoteDelivery(Fixtures.RETAIL, BigDecimal.ONE, 2, Fixtures.RETAIL);
        assertThat(quote.sellerFee().minor()).isZero();
        assertThat(quote.sellerFeeSource()).isEqualTo("RETAIL");
        PaymentSplit split = splitter.split(List.of(Fixtures.deliveryLine("R1", Fixtures.RETAIL, "Groceries", 1, 1000,
                599, 83, 200)), schedule, Fixtures.usd(60));
        RecipientShare retail = share(split, Fixtures.RETAIL);
        assertThat(retail.deliveryFees().minor()).isZero();
        assertThat(retail.shipping().minor()).isEqualTo(599);
        assertThat(split.platform().shipping().minor()).isZero();
        assertThat(retail.net().minor()).isEqualTo(1000 + 599 - retail.stripeFee().minor());
    }

    /**
     * Retail lines (one FBA, one self-shipped) with two marketplace sellers (one FBA, one self-shipped): Retail pays
     * no commission and no FBA fee and keeps all its shipping; sellers pay as before.
     */
    @Test
    void mixedRetailAndTwoSellersOrder() {
        List<OrderLine> lines = List.of(
                Fixtures.line("R1", Fixtures.RETAIL, "Groceries", Fulfilment.FBA, 2, 2400, 400, 198),
                Fixtures.line("R2", Fixtures.RETAIL, "Fashion", Fulfilment.SELF, 1, 1500, 300, 124),
                Fixtures.line("A1", "org_a", "Groceries", Fulfilment.SELF, 2, 2000, 500, 165),
                Fixtures.line("B1", "org_b", "Fashion", Fulfilment.FBA, 3, 3000, 600, 248));
        FeeSchedule schedule = Fixtures.schedule();
        Money fee = schedule.estimateCardFee(Fixtures.usd(11_435));
        assertThat(fee.minor()).isEqualTo(362);

        PaymentSplit split = splitter.split(lines, schedule, fee);

        RecipientShare platform = split.platform();
        RecipientShare retail = share(split, Fixtures.RETAIL);
        RecipientShare a = share(split, "org_a");
        RecipientShare b = share(split, "org_b");
        assertThat(split.charge().minor()).isEqualTo(11_435);
        assertThat(retail.items().minor()).isEqualTo(3900);
        assertThat(retail.shipping().minor()).isEqualTo(700);
        assertThat(retail.tax().minor()).isEqualTo(322);
        assertThat(retail.commission().minor()).isZero();
        assertThat(retail.fbaFees().minor()).isZero();
        assertThat(a.commission().minor()).isEqualTo(160);
        assertThat(a.fbaFees().minor()).isZero();
        assertThat(b.commission().minor()).isEqualTo(360);
        assertThat(b.fbaFees().minor()).isEqualTo(975);
        assertThat(b.shipping().minor()).isZero();
        assertThat(platform.shipping().minor()).isEqualTo(600);
        // Weights 600 / 4922 / 2665 / 3248: exact 18.99 / 155.82 / 84.37 / 102.82 → floors 359; the three leftover
        // cents go to AfroTransact, org_b and Retail (largest remainders).
        assertThat(platform.stripeFee().minor()).isEqualTo(19);
        assertThat(retail.stripeFee().minor()).isEqualTo(156);
        assertThat(a.stripeFee().minor()).isEqualTo(84);
        assertThat(b.stripeFee().minor()).isEqualTo(103);
        assertThat(retail.net().minor()).isEqualTo(3900 + 700 - 156);
        assertThat(a.net().minor()).isEqualTo(2000 + 500 - 160 - 84);
        assertThat(b.net().minor()).isEqualTo(3000 - 360 - 975 - 103);
        assertThat(platform.net().minor()).isEqualTo(600 + 520 + 975 - 19);
        long nets = split.recipients().stream().mapToLong(r -> r.net().minor()).sum();
        assertThat(nets + split.totalTax().minor()).isEqualTo(11_435 - 362);
        assertThat(split.line("R1").orElseThrow().fbaFee().minor()).isZero();
        assertThat(split.line("R1").orElseThrow().shippingToPlatform()).isFalse();
        assertThat(split.line("B1").orElseThrow().shippingToPlatform()).isTrue();
    }

    @Test
    void retailOnlyOrderPaysOnlyItsStripeFee() {
        List<OrderLine> lines = List.of(
                Fixtures.line("R1", Fixtures.RETAIL, "Groceries", Fulfilment.FBA, 2, 2400, 400, 198),
                Fixtures.line("R2", Fixtures.RETAIL, "Fashion", Fulfilment.SELF, 1, 1500, 300, 124));
        FeeSchedule schedule = Fixtures.schedule();
        Money fee = schedule.estimateCardFee(Fixtures.usd(4922));
        assertThat(fee.minor()).isEqualTo(173);

        PaymentSplit split = splitter.split(lines, schedule, fee);

        RecipientShare retail = share(split, Fixtures.RETAIL);
        assertThat(split.recipients()).hasSize(2);
        assertThat(retail.commission().minor()).isZero();
        assertThat(retail.fbaFees().minor()).isZero();
        assertThat(retail.stripeFee().minor()).isEqualTo(173);
        assertThat(retail.net().minor()).isEqualTo(3900 + 700 - 173);
        RecipientShare platform = split.platform();
        assertThat(platform.commission().minor()).isZero();
        assertThat(platform.fbaFees().minor()).isZero();
        assertThat(platform.shipping().minor()).isZero();
        assertThat(platform.net().minor()).isZero();
        assertThat(retail.net().minor() + split.totalTax().minor()).isEqualTo(4922 - 173);
    }

    @Test
    void afroTransactRetailPaysNoCommissionEvenWithAnOverride() {
        FeeSchedule schedule = Fixtures.schedule(1, Instant.parse("2020-01-01T00:00:00Z"),
                List.of(new CommissionOverride(Fixtures.RETAIL, "*", 5000)));
        PaymentSplit split = splitter.split(List.of(
                Fixtures.line("L1", Fixtures.RETAIL, "Fashion", Fulfilment.FBA, 1, 10_000, 0, 0)), schedule,
                Fixtures.usd(0));
        assertThat(share(split, Fixtures.RETAIL).commission().minor()).isZero();
        assertThat(split.lines().getFirst().commissionBps()).isZero();
        assertThat(share(split, Fixtures.RETAIL).fbaFees().minor()).isZero();
    }

    @Test
    void sellerOverrideBeatsCategoryRate() {
        FeeSchedule schedule = Fixtures.schedule(1, Instant.parse("2020-01-01T00:00:00Z"), List.of(
                new CommissionOverride("org_a", "fashion", 500), new CommissionOverride("org_b", "*", 700)));
        PaymentSplit split = splitter.split(List.of(
                Fixtures.line("L1", "org_a", "Fashion", Fulfilment.SELF, 1, 10_000, 0, 0),
                Fixtures.line("L2", "org_a", "Groceries", Fulfilment.SELF, 1, 10_000, 0, 0),
                Fixtures.line("L3", "org_b", "Fashion", Fulfilment.SELF, 1, 10_000, 0, 0),
                Fixtures.line("L4", "org_c", "Unknown", Fulfilment.SELF, 1, 10_000, 0, 0)), schedule,
                Fixtures.usd(0));
        assertThat(split.lines()).extracting(LineSplit::commissionBps).containsExactly(500, 800, 700, 1000);
    }

    @Test
    void wholeFeeGoesToAfroTransactWhenNothingIsCharged() {
        PaymentSplit split = splitter.split(List.of(
                Fixtures.line("L1", "org_a", "Groceries", Fulfilment.SELF, 1, 0, 0, 0)), Fixtures.schedule(),
                Fixtures.usd(30));
        assertThat(split.platform().stripeFee().minor()).isEqualTo(30);
        assertThat(share(split, "org_a").stripeFee().minor()).isZero();
    }

    @Test
    void rejectsCurrencyMismatchAndEmptyOrders() {
        assertThatThrownBy(() -> splitter.split(List.of(), Fixtures.schedule(), Fixtures.usd(0)))
                .isInstanceOf(PaymentsRuleViolation.class);
        OrderLine eur = new OrderLine("L1", "org_a", "x", Fulfilment.SELF, 1, Money.of(100, "EUR"),
                Money.of(0, "EUR"), Money.of(0, "EUR"), null);
        assertThatThrownBy(() -> splitter.split(List.of(eur), Fixtures.schedule(), Money.of(0, "EUR")))
                .isInstanceOf(PaymentsRuleViolation.class);
    }

    /**
     * Property: for thousands of random multi-seller orders and fees, the split reconciles exactly to the charge,
     * fee shares are within one cent of the exact pro-rata share, and Retail never pays commission or FBA fees and
     * keeps the shipping of all its lines (net = items + shipping − fee share).
     */
    @Test
    void randomSplitsAlwaysReconcileToTheCharge() {
        Random random = new Random(20261010);
        String[] orgs = {"org_a", "org_b", "org_c", Fixtures.RETAIL};
        String[] categories = {"Groceries", "Fashion", "Services", "Books"};
        FeeSchedule schedule = Fixtures.schedule(1, Instant.parse("2020-01-01T00:00:00Z"),
                List.of(new CommissionOverride("org_c", "*", 333)));
        for (int run = 0; run < 5_000; run++) {
            List<OrderLine> lines = new ArrayList<>();
            int n = 1 + random.nextInt(6);
            for (int i = 0; i < n; i++) {
                // Every other order has at least one Retail line.
                String org = i == 0 && run % 2 == 0 ? Fixtures.RETAIL : orgs[random.nextInt(orgs.length)];
                Fulfilment f = Fulfilment.values()[random.nextInt(Fulfilment.values().length)];
                String category = categories[random.nextInt(categories.length)];
                int qty = 1 + random.nextInt(5);
                long items = random.nextInt(50_000);
                long ship = random.nextInt(2_000);
                long lineTax = random.nextInt(4_000);
                lines.add(f == Fulfilment.AFROTRANSACT_DELIVERY
                        ? Fixtures.deliveryLine("L" + i, org, category, qty, items, ship, lineTax, random.nextInt(1_500))
                        : Fixtures.line("L" + i, org, category, f, qty, items, ship, lineTax));
            }
            long charge = lines.stream().mapToLong(l -> l.total().minor()).sum();
            long fee = charge == 0 ? random.nextInt(50) : random.nextLong(Math.max(1, charge / 10));
            PaymentSplit split = splitter.split(lines, schedule, Fixtures.usd(fee));

            long nets = split.recipients().stream().mapToLong(r -> r.net().minor()).sum();
            long tax = split.totalTax().minor();
            long shares = split.recipients().stream().mapToLong(r -> r.stripeFee().minor()).sum();
            assertThat(split.charge().minor()).isEqualTo(charge);
            assertThat(shares).isEqualTo(fee);
            assertThat(nets + tax).isEqualTo(charge - fee);
            for (RecipientShare r : split.recipients()) {
                if (charge > 0) {
                    double exact = (double) fee * r.gross().minor() / charge;
                    assertThat(Math.abs(r.stripeFee().minor() - exact)).isLessThan(1.0);
                }
                if (r.kind() == RecipientKind.RETAIL) {
                    long items = lines.stream().filter(l -> l.sellerOrg().equals(Fixtures.RETAIL))
                            .mapToLong(l -> l.items().minor()).sum();
                    long shipping = lines.stream().filter(l -> l.sellerOrg().equals(Fixtures.RETAIL))
                            .mapToLong(l -> l.shipping().minor()).sum();
                    assertThat(r.commission().minor()).isZero();
                    assertThat(r.fbaFees().minor()).isZero();
                    assertThat(r.deliveryFees().minor()).isZero();
                    assertThat(r.net().minor()).isEqualTo(items + shipping - r.stripeFee().minor());
                } else if (r.kind() == RecipientKind.SELLER) {
                    long deliveryFees = lines.stream().filter(l -> l.sellerOrg().equals(r.recipientId())
                            && l.fulfilment() == Fulfilment.AFROTRANSACT_DELIVERY)
                            .mapToLong(l -> l.deliveryFee().minor()).sum();
                    assertThat(r.deliveryFees().minor()).isEqualTo(deliveryFees);
                    assertThat(r.net().minor()).isEqualTo(r.items().minor() + r.shipping().minor()
                            - r.commission().minor() - r.fbaFees().minor() - deliveryFees - r.stripeFee().minor());
                }
            }
            long platformIncome = split.sellers().stream()
                    .mapToLong(r -> r.commission().minor() + r.fbaFees().minor() + r.deliveryFees().minor()).sum();
            assertThat(split.platform().commission().minor() + split.platform().fbaFees().minor()
                    + split.platform().deliveryFees().minor()).isEqualTo(platformIncome);
            // Marketplace sellers' FBA and AfroTransact Delivery shipping is AfroTransact's (4200); never Retail's.
            long platformShipping = lines.stream().filter(l -> l.fulfilment().platformShips()
                    && !l.sellerOrg().equals(Fixtures.RETAIL)).mapToLong(l -> l.shipping().minor()).sum();
            assertThat(split.platform().shipping().minor()).isEqualTo(platformShipping);
            assertThat(split.lines()).filteredOn(l -> !l.sellerOrg().equals(Fixtures.RETAIL)
                            && lines.stream().anyMatch(x -> x.lineId().equals(l.lineId())
                            && x.fulfilment() == Fulfilment.AFROTRANSACT_DELIVERY))
                    .allSatisfy(l -> assertThat(l.fbaFee().minor()).isZero());
            Journal journal = Postings.capture(UUID.randomUUID(), Instant.EPOCH, "k" + run, "o", "p", "c",
                    split);
            assertThat(journal.lines()).isNotEmpty();
            assertThat(split.lines()).filteredOn(l -> l.sellerOrg().equals(Fixtures.RETAIL))
                    .allSatisfy(l -> {
                        assertThat(l.commission().minor()).isZero();
                        assertThat(l.fbaFee().minor()).isZero();
                        assertThat(l.deliveryFee().minor()).isZero();
                        assertThat(l.shippingToPlatform()).isFalse();
                    });
        }
    }

    private static RecipientShare share(PaymentSplit split, String id) {
        return split.recipients().stream().filter(r -> r.recipientId().equals(id)).findFirst().orElseThrow();
    }
}
