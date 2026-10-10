package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PostingsTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private final PaymentSplitter splitter = new DefaultPaymentSplitter(Fixtures.RETAIL);

    @Test
    void captureJournalBalancesForRandomSplits() {
        Random random = new Random(7);
        String[] orgs = {"org_a", "org_b", Fixtures.RETAIL};
        for (int run = 0; run < 2_000; run++) {
            List<OrderLine> lines = new ArrayList<>();
            for (int i = 0; i < 1 + random.nextInt(5); i++) {
                lines.add(Fixtures.line("L" + i, orgs[random.nextInt(orgs.length)], "Fashion",
                        random.nextBoolean() ? Fulfilment.FBA : Fulfilment.SELF, 1 + random.nextInt(9),
                        random.nextInt(20_000), random.nextInt(1_500), random.nextInt(2_000)));
            }
            long charge = lines.stream().mapToLong(l -> l.total().minor()).sum();
            PaymentSplit split = splitter.split(lines, Fixtures.schedule(),
                    Fixtures.usd(random.nextLong(Math.max(1, charge / 8 + 1))));
            Journal j = Postings.capture(UUID.randomUUID(), NOW, "capture:" + run, "o", "p", "ch_1", split);
            assertThat(Journal.imbalance(j.lines())).isEmpty();
            long clearing = j.lines().stream().filter(l -> l.account().chart() == Chart.STRIPE_CLEARING)
                    .mapToLong(JournalLine::signedMinor).sum();
            assertThat(clearing).isEqualTo(split.charge().minus(split.stripeFee()).minor());
        }
    }

    @Test
    void captureCreditsEachSellersNetAndTax() {
        PaymentSplit split = splitter.split(List.of(
                Fixtures.line("L1", "org_a", "Groceries", Fulfilment.SELF, 1, 10_000, 500, 825)),
                Fixtures.schedule(), Fixtures.usd(350));
        Journal j = Postings.capture(UUID.randomUUID(), NOW, "k", "o", "p", "ch_1", split);
        assertThat(credit(j, LedgerAccount.sellerPayable("org_a"))).isEqualTo(10_000 + 500 - 800 - 350);
        assertThat(credit(j, LedgerAccount.salesTax("US-TX"))).isEqualTo(825);
        assertThat(credit(j, LedgerAccount.of(Chart.COMMISSION_REVENUE))).isEqualTo(800);
    }

    @Test
    void refundReversesCommissionCumulativelyAndBalances() {
        PaymentSplit split = splitter.split(List.of(
                Fixtures.line("L1", "org_a", "Groceries", Fulfilment.SELF, 3, 1001, 300, 90)),
                Fixtures.schedule(), Fixtures.usd(0));
        PaymentLine pl = PaymentLine.uncaptured(Fixtures.line("L1", "org_a", "Groceries", Fulfilment.SELF, 3,
                1001, 300, 90)).captured(UUID.randomUUID(), split.lines().getFirst());
        assertThat(pl.commission().minor()).isEqualTo(80);
        RefundLine first = RefundLine.allocate(pl, Fixtures.usd(333), Fixtures.usd(0), Fixtures.usd(30), true);
        pl = pl.refunded(first);
        RefundLine second = RefundLine.allocate(pl, Fixtures.usd(334), Fixtures.usd(0), Fixtures.usd(30), true);
        pl = pl.refunded(second);
        RefundLine third = RefundLine.allocate(pl, Fixtures.usd(334), Fixtures.usd(300), Fixtures.usd(30), true);
        pl = pl.refunded(third);
        assertThat(first.commissionReversed().minor() + second.commissionReversed().minor()
                + third.commissionReversed().minor()).isEqualTo(80);
        assertThat(pl.refundedTotal()).isEqualTo(pl.line().total());
        Journal j = Postings.refund(UUID.randomUUID(), NOW, "r", "o", "p", "re_1", List.of(third));
        assertThat(Journal.imbalance(j.lines())).isEmpty();
        assertThat(credit(j, LedgerAccount.of(Chart.CUSTOMER_REFUNDS_PAYABLE))).isEqualTo(664);
        assertThat(debit(j, LedgerAccount.sellerPayable("org_a")))
                .isEqualTo(334 + 300 - third.commissionReversed().minor());
        PaymentLine done = pl;
        assertThatThrownBy(() -> RefundLine.allocate(done, Fixtures.usd(1), Fixtures.usd(0), Fixtures.usd(0), true))
                .isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.code()).isEqualTo("payments.refund-exceeds-line"));
    }

    @Test
    void retainedCommissionPolicyGivesNothingBack() {
        PaymentSplit split = splitter.split(List.of(
                Fixtures.line("L1", "org_a", "Groceries", Fulfilment.SELF, 1, 1000, 0, 0)), Fixtures.schedule(),
                Fixtures.usd(0));
        PaymentLine pl = PaymentLine.uncaptured(Fixtures.line("L1", "org_a", "Groceries", Fulfilment.SELF, 1, 1000,
                0, 0)).captured(UUID.randomUUID(), split.lines().getFirst());
        RefundLine r = RefundLine.allocate(pl, Fixtures.usd(1000), Fixtures.usd(0), Fixtures.usd(0), false);
        assertThat(r.commissionReversed().minor()).isZero();
        assertThat(r.sellerDebit().minor()).isEqualTo(1000);
    }

    @Test
    void uncapturedLinesCannotBeRefunded() {
        PaymentLine pl = PaymentLine.uncaptured(Fixtures.line("L1", "org_a", "x", Fulfilment.SELF, 1, 10, 0, 0));
        assertThatThrownBy(() -> RefundLine.allocate(pl, Fixtures.usd(1), Fixtures.usd(0), Fixtures.usd(0), true))
                .isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.code()).isEqualTo("payments.line-not-captured"));
    }

    @Test
    void disputeJournalsBalanceForEveryOutcome() {
        Map<String, Money> charged = new LinkedHashMap<>();
        charged.put("org_a", Fixtures.usd(7000));
        charged.put("org_b", Fixtures.usd(2999));
        charged.put(PaymentSplitter.PLATFORM, Fixtures.usd(500));
        List<DisputeAllocation> allocations = Dispute.allocate(Fixtures.usd(5000), charged);
        assertThat(allocations.stream().mapToLong(a -> a.amount().minor()).sum()).isEqualTo(5000);
        Dispute d = new Dispute("dp_1", UUID.randomUUID(), "o", "ch_1", Fixtures.usd(5000), Fixtures.usd(1500),
                DisputeStatus.OPEN, "fraudulent", allocations, NOW, null);
        Journal opened = Postings.disputeOpened(UUID.randomUUID(), NOW, "o1", d);
        Journal won = Postings.disputeWon(UUID.randomUUID(), NOW, "w", d.close(DisputeStatus.WON, NOW));
        Journal lost = Postings.disputeLost(UUID.randomUUID(), NOW, "l", d.close(DisputeStatus.LOST, NOW));
        assertThat(debit(opened, LedgerAccount.sellerPayable("org_a"))).isEqualTo(allocations.stream()
                .filter(a -> a.recipientId().equals("org_a")).findFirst().orElseThrow().amount().minor());
        assertThat(credit(opened, LedgerAccount.of(Chart.STRIPE_CLEARING))).isEqualTo(6500);
        assertThat(credit(won, LedgerAccount.sellerPayable("org_b"))).isPositive();
        assertThat(debit(lost, LedgerAccount.of(Chart.CHARGEBACK_LOSSES))).isPositive();
        for (Journal j : List.of(opened, won, lost)) {
            assertThat(Journal.imbalance(j.lines())).isEmpty();
        }
    }

    private static long credit(Journal j, LedgerAccount a) {
        return sum(j, a, EntrySide.CREDIT);
    }

    private static long debit(Journal j, LedgerAccount a) {
        return sum(j, a, EntrySide.DEBIT);
    }

    private static long sum(Journal j, LedgerAccount a, EntrySide side) {
        return j.lines().stream().filter(l -> l.account().equals(a) && l.side() == side)
                .mapToLong(l -> l.amount().minor()).sum();
    }
}
