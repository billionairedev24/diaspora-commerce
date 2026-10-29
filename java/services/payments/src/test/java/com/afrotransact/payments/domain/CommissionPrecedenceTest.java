package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Commission (products) and connection fee (services): the first match wins: active negotiated rate (category, then
 * *) → plan rate (category, then the plan's *) → category rate → default; AfroTransact Retail pays none.
 */
class CommissionPrecedenceTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final String ORG = "org_a";

    /** Groceries 8 %, Fashion 12 %, default 10 %; Growth: groceries 7 %, * 9 %; connection fee 10 %, Hair 9 %. */
    private static FeeSchedule schedule(List<CommissionOverride> overrides) {
        return new FeeSchedule(UUID.randomUUID(), 1, FROM, "USD", Map.of("groceries", 800, "fashion", 1200), 1000,
                overrides, 290, Fixtures.usd(30), Fixtures.usd(325), true, null, "test", FROM, null, null,
                Map.of("seller_growth", Map.of("groceries", 700, "*", 900), "seller_essentials", Map.of("*", 1500)),
                new RateTable(1000, Map.of("hair & beauty", 900),
                        Map.of("provider_studio", Map.of("*", 800), "provider_business", Map.of("massage", 500))));
    }

    private static NegotiatedRate negotiated(NegotiatedRateType type, String category, int bps, Instant starts,
                                             Instant ends) {
        return new NegotiatedRate(UUID.randomUUID(), ORG, type, category, bps, starts, ends, "Launch partner",
                "fin-1", "CRM-42", "fin-1", starts, null, null, null, null, 0);
    }

    @Test
    void defaultThenCategoryThenPlanThenNegotiatedAndCategoryBeatsStar() {
        FeeSchedule s = schedule(List.of());
        AppliedRate def = s.rateFor(FeeKind.PRODUCT, ORG, "books", null, List.of(), NOW, Fixtures.RETAIL);
        assertThat(def.bps()).isEqualTo(1000);
        assertThat(def.source()).isEqualTo(RateSource.DEFAULT);

        AppliedRate cat = s.rateFor(FeeKind.PRODUCT, ORG, "Fashion", null, List.of(), NOW, Fixtures.RETAIL);
        assertThat(cat.bps()).isEqualTo(1200);
        assertThat(cat.source()).isEqualTo(RateSource.CATEGORY);
        assertThat(cat.category()).isEqualTo("fashion");

        // Plan: its category first, then its * rate, which also beats the schedule's category rate.
        AppliedRate planCat = s.rateFor(FeeKind.PRODUCT, ORG, "groceries", "seller_growth", List.of(), NOW,
                Fixtures.RETAIL);
        assertThat(planCat.bps()).isEqualTo(700);
        assertThat(planCat.source()).isEqualTo(RateSource.PLAN);
        assertThat(planCat.planCode()).isEqualTo("seller_growth");
        AppliedRate planStar = s.rateFor(FeeKind.PRODUCT, ORG, "fashion", "SELLER_GROWTH", List.of(), NOW,
                Fixtures.RETAIL);
        assertThat(planStar.bps()).isEqualTo(900);
        assertThat(planStar.source()).isEqualTo(RateSource.PLAN);
        // A plan without rates in this version falls through to the category rate.
        assertThat(s.rateFor(FeeKind.PRODUCT, ORG, "fashion", "seller_pro", List.of(), NOW, Fixtures.RETAIL).source())
                .isEqualTo(RateSource.CATEGORY);

        NegotiatedRate star = negotiated(NegotiatedRateType.COMMISSION, "*", 600, FROM, null);
        NegotiatedRate fashion = negotiated(NegotiatedRateType.COMMISSION, "fashion", 500, FROM, null);
        AppliedRate n = s.rateFor(FeeKind.PRODUCT, ORG, "groceries", "seller_growth", List.of(star, fashion), NOW,
                Fixtures.RETAIL);
        assertThat(n.bps()).isEqualTo(600);
        assertThat(n.source()).isEqualTo(RateSource.OVERRIDE);
        assertThat(n.negotiatedRateId()).isEqualTo(star.id());
        AppliedRate nCat = s.rateFor(FeeKind.PRODUCT, ORG, "fashion", "seller_growth", List.of(star, fashion), NOW,
                Fixtures.RETAIL);
        assertThat(nCat.bps()).isEqualTo(500);
        assertThat(nCat.negotiatedRateId()).isEqualTo(fashion.id());
    }

    @Test
    void negotiatedRatesOnlyApplyBetweenTheirDatesAndOnlyToTheirOrgAndType() {
        FeeSchedule s = schedule(List.of());
        Instant start = NOW.minus(Duration.ofDays(1));
        Instant end = NOW.plus(Duration.ofDays(30));
        NegotiatedRate r = negotiated(NegotiatedRateType.COMMISSION, "*", 500, start, end);
        assertThat(s.rateFor(FeeKind.PRODUCT, ORG, "books", null, List.of(r), start.minusSeconds(1), Fixtures.RETAIL)
                .source()).isEqualTo(RateSource.DEFAULT);
        AppliedRate inside = s.rateFor(FeeKind.PRODUCT, ORG, "books", null, List.of(r), start, Fixtures.RETAIL);
        assertThat(inside.bps()).isEqualTo(500);
        assertThat(inside.negotiatedUntil()).isEqualTo(end);
        assertThat(s.rateFor(FeeKind.PRODUCT, ORG, "books", null, List.of(r), end, Fixtures.RETAIL).bps())
                .isEqualTo(1000);
        assertThat(s.rateFor(FeeKind.PRODUCT, "org_b", "books", null, List.of(r), NOW, Fixtures.RETAIL).bps())
                .isEqualTo(1000);
        // A commission deal never changes the connection fee.
        assertThat(s.rateFor(FeeKind.SERVICE, ORG, "massage", null, List.of(r), NOW, Fixtures.RETAIL).bps())
                .isEqualTo(1000);
        // Ended early: the plan / category rate applies again from that moment.
        NegotiatedRate ended = r.end(NOW, "fin-2", "Contract terminated");
        assertThat(ended.activeAt(NOW.minusSeconds(1))).isTrue();
        assertThat(s.rateFor(FeeKind.PRODUCT, ORG, "fashion", null, List.of(ended), NOW, Fixtures.RETAIL).bps())
                .isEqualTo(1200);
    }

    @Test
    void connectionFeesHaveTheirOwnSectionWithTheSameLevels() {
        FeeSchedule s = schedule(List.of(new CommissionOverride(ORG, "*", 300)));
        AppliedRate def = s.rateFor(FeeKind.SERVICE, ORG, "massage", null, List.of(), NOW, Fixtures.RETAIL);
        assertThat(def.bps()).isEqualTo(1000);
        assertThat(def.source()).isEqualTo(RateSource.DEFAULT);
        assertThat(def.kind()).isEqualTo(FeeKind.SERVICE);
        assertThat(s.rateFor(FeeKind.SERVICE, ORG, "Hair & Beauty", null, List.of(), NOW, Fixtures.RETAIL).bps())
                .isEqualTo(900);
        assertThat(s.rateFor(FeeKind.SERVICE, ORG, "hair & beauty", "provider_studio", List.of(), NOW,
                Fixtures.RETAIL).bps()).isEqualTo(800);
        assertThat(s.rateFor(FeeKind.SERVICE, ORG, "massage", "provider_business", List.of(), NOW,
                Fixtures.RETAIL).bps()).isEqualTo(500);
        assertThat(s.rateFor(FeeKind.SERVICE, ORG, "nails", "provider_business", List.of(), NOW,
                Fixtures.RETAIL).source()).isEqualTo(RateSource.DEFAULT);
        NegotiatedRate deal = negotiated(NegotiatedRateType.CONNECTION_FEE, "*", 400, FROM, null);
        AppliedRate n = s.rateFor(FeeKind.SERVICE, ORG, "massage", "provider_business", List.of(deal), NOW,
                Fixtures.RETAIL);
        assertThat(n.bps()).isEqualTo(400);
        assertThat(n.source()).isEqualTo(RateSource.OVERRIDE);
        // The version's product commission overrides never touch services.
        assertThat(s.rateFor(FeeKind.PRODUCT, ORG, "books", null, List.of(), NOW, Fixtures.RETAIL).bps())
                .isEqualTo(300);
    }

    @Test
    void versionsWithoutAConnectionFeeSectionPriceServicesLikeBefore() {
        FeeSchedule legacy = Fixtures.schedule();
        AppliedRate r = legacy.rateFor(FeeKind.SERVICE, ORG, "hair & beauty", null, List.of(), NOW, Fixtures.RETAIL);
        assertThat(r.bps()).isEqualTo(1000);
        assertThat(r.source()).isEqualTo(RateSource.DEFAULT);
        assertThat(FeeKind.of(null, "Services")).isEqualTo(FeeKind.SERVICE);
        assertThat(FeeKind.of(null, "groceries")).isEqualTo(FeeKind.PRODUCT);
        assertThat(FeeKind.of("service", "groceries")).isEqualTo(FeeKind.SERVICE);
        assertThatThrownBy(() -> FeeKind.of("rental", null)).isInstanceOf(PaymentsRuleViolation.class);
    }

    @Test
    void retailNeverPaysWhateverIsNegotiatedOrPlanned() {
        FeeSchedule s = schedule(List.of(new CommissionOverride(Fixtures.RETAIL, "*", 500)));
        NegotiatedRate deal = new NegotiatedRate(UUID.randomUUID(), Fixtures.RETAIL, NegotiatedRateType.COMMISSION,
                "*", 500, FROM, null, "x", "a", null, "a", FROM, null, null, null, null, 0);
        for (FeeKind kind : FeeKind.values()) {
            AppliedRate r = s.rateFor(kind, Fixtures.RETAIL, "groceries", "seller_growth", List.of(deal), NOW,
                    Fixtures.RETAIL);
            assertThat(r.bps()).isZero();
            assertThat(r.source()).isEqualTo(RateSource.RETAIL);
        }
        EffectiveFees e = s.effectiveFor(FeeKind.SERVICE, ORG, "massage", "provider_studio", List.of(), NOW,
                Fixtures.RETAIL);
        assertThat(e.commissionBps()).isEqualTo(800);
        assertThat(e.commissionSource()).isEqualTo("PLAN");
        assertThat(e.planCode()).isEqualTo("provider_studio");
        assertThat(e.fbaPerUnit().minor()).isZero();
    }

    @Test
    void theSnapshotOnTheLineNotTheScheduleDecidesTheCommission() {
        FeeSchedule s = schedule(List.of());
        AppliedRate atPlacement = s.rateFor(FeeKind.PRODUCT, ORG, "groceries", "seller_essentials", List.of(), NOW,
                Fixtures.RETAIL);
        assertThat(atPlacement.bps()).isEqualTo(1500);
        OrderLine placed = Fixtures.line("L1", ORG, "groceries", Fulfilment.SELF, 1, 10_000, 0, 0)
                .withRate(atPlacement);
        // Later the seller moves to Growth and a deal is negotiated: the placed line keeps 15 %.
        PaymentSplit split = new DefaultPaymentSplitter(Fixtures.RETAIL).split(List.of(placed), s, Fixtures.usd(0));
        assertThat(split.line("L1").orElseThrow().commission().minor()).isEqualTo(1500);
        assertThat(split.line("L1").orElseThrow().rateSource()).isEqualTo("PLAN");
        // Lines created before snapshots existed keep their version's commission (no plan rates).
        OrderLine legacy = Fixtures.line("L2", ORG, "groceries", Fulfilment.SELF, 1, 10_000, 0, 0);
        PaymentSplit old = new DefaultPaymentSplitter(Fixtures.RETAIL).split(List.of(legacy), s, Fixtures.usd(0));
        assertThat(old.line("L2").orElseThrow().commission().minor()).isEqualTo(800);
        assertThat(old.line("L2").orElseThrow().rateSource()).isNull();
        assertThat(placed.withoutRate()).isEqualTo(Fixtures.line("L1", ORG, "groceries", Fulfilment.SELF, 1, 10_000,
                0, 0));
    }

    @Test
    void serviceLinesPayAConnectionFeeBookedTo4050AndRefundsReverseIt() {
        FeeSchedule s = schedule(List.of());
        OrderLine deposit = new OrderLine("deposit", ORG, "massage", Fulfilment.SELF, 1, Fixtures.usd(5_000),
                Fixtures.usd(0), Fixtures.usd(0), "US", null, null, FeeKind.SERVICE,
                s.rateFor(FeeKind.SERVICE, ORG, "massage", "provider_studio", List.of(), NOW, Fixtures.RETAIL));
        PaymentSplit split = new DefaultPaymentSplitter(Fixtures.RETAIL).split(List.of(deposit), s,
                Fixtures.usd(175));
        RecipientShare provider = split.sellers().getFirst();
        assertThat(provider.connectionFees().minor()).isEqualTo(400);
        assertThat(provider.commission().minor()).isZero();
        assertThat(split.platform().connectionFees().minor()).isEqualTo(400);
        assertThat(split.platform().commission().minor()).isZero();
        assertThat(split.line("deposit").orElseThrow().connectionFee().minor()).isEqualTo(400);
        assertThat(split.line("deposit").orElseThrow().kind()).isEqualTo(FeeKind.SERVICE);

        Journal capture = Postings.capture(UUID.randomUUID(), NOW, "capture:x", "BK-1", "p1", "ch_1", split);
        assertThat(credit(capture, Chart.CONNECTION_FEE_REVENUE)).isEqualTo(400);
        assertThat(credit(capture, Chart.COMMISSION_REVENUE)).isZero();

        PaymentLine captured = PaymentLine.uncaptured(deposit).captured(UUID.randomUUID(),
                split.line("deposit").orElseThrow());
        RefundLine half = RefundLine.allocate(captured, Fixtures.usd(2_500), Fixtures.usd(0), Fixtures.usd(0), true);
        assertThat(half.connectionFee()).isTrue();
        assertThat(half.commissionReversed().minor()).isEqualTo(200);
        Journal refund = Postings.refund(UUID.randomUUID(), NOW, "refund:x", "BK-1", "p1", "re_1", List.of(half),
                Fixtures.RETAIL);
        assertThat(debit(refund, Chart.CONNECTION_FEE_REVENUE)).isEqualTo(200);
        assertThat(debit(refund, Chart.COMMISSION_REVENUE)).isZero();
        // With reverseCommissionOnRefund off, AfroTransact keeps the connection fee.
        assertThat(RefundLine.allocate(captured, Fixtures.usd(2_500), Fixtures.usd(0), Fixtures.usd(0), false)
                .commissionReversed().minor()).isZero();
    }

    private static long credit(Journal j, Chart account) {
        return j.lines().stream().filter(l -> l.account().chart() == account && l.side() == EntrySide.CREDIT)
                .mapToLong(l -> l.amount().minor()).sum();
    }

    private static long debit(Journal j, Chart account) {
        return j.lines().stream().filter(l -> l.account().chart() == account && l.side() == EntrySide.DEBIT)
                .mapToLong(l -> l.amount().minor()).sum();
    }
}
