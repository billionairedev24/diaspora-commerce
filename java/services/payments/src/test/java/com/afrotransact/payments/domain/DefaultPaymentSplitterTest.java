package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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
        assertThat(retail.fbaFees().minor()).isEqualTo(325);
        assertThat(b.fbaFees().minor()).isEqualTo(975);
        assertThat(platform.shipping().minor()).isEqualTo(300);
        // Largest remainder: exact 9.95 / 88.40 / 35.92 / 107.73 → floors 239, three cents to the largest remainders.
        assertThat(platform.stripeFee().minor()).isEqualTo(10);
        assertThat(a.stripeFee().minor()).isEqualTo(88);
        assertThat(retail.stripeFee().minor()).isEqualTo(36);
        assertThat(b.stripeFee().minor()).isEqualTo(108);
        assertThat(a.net().minor()).isEqualTo(2252);
        assertThat(retail.net().minor()).isEqualTo(639);
        assertThat(b.net().minor()).isEqualTo(1557);
        assertThat(platform.commission().minor()).isEqualTo(520);
        assertThat(platform.fbaFees().minor()).isEqualTo(1300);
        assertThat(platform.net().minor()).isEqualTo(2110);
        assertThat(split.totalTax().minor()).isEqualTo(496);
        assertThat(a.taxByJurisdiction()).containsEntry("US-TX", Fixtures.usd(165));
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
     * fee shares are within one cent of the exact pro-rata share, and Retail never pays commission.
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
                lines.add(Fixtures.line("L" + i, orgs[random.nextInt(orgs.length)],
                        categories[random.nextInt(categories.length)],
                        random.nextBoolean() ? Fulfilment.FBA : Fulfilment.SELF, 1 + random.nextInt(5),
                        random.nextInt(50_000), random.nextInt(2_000), random.nextInt(4_000)));
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
                    assertThat(r.commission().minor()).isZero();
                }
            }
            long platformIncome = split.sellers().stream()
                    .mapToLong(r -> r.commission().minor() + r.fbaFees().minor()).sum();
            assertThat(split.platform().commission().minor() + split.platform().fbaFees().minor())
                    .isEqualTo(platformIncome);
        }
    }

    private static RecipientShare share(PaymentSplit split, String id) {
        return split.recipients().stream().filter(r -> r.recipientId().equals(id)).findFirst().orElseThrow();
    }
}
