package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** ADR 0005 §3: one resolution of the staff-configured delivery fee, by method. */
class DeliveryFeesTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final UUID DOOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID PICKUP_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID CARRIER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d3");

    private static BigDecimal mi(String v) {
        return new BigDecimal(v);
    }

    /** $5.99 up to 10 miles, $7.99 up to 40, $12.99 up to 80; pickup $1; carrier +$2 handling, $8 fallback, free over $75. */
    private static DeliveryFees fees(WindowDistances distances) {
        DeliveryFeeRule door = new DeliveryFeeRule(DOOR_ID, FeeMethod.AFROTRANSACT_DELIVERY, "USD", List.of(
                new FeeBand(mi("0"), mi("10"), 599), new FeeBand(mi("10"), mi("40"), 799),
                new FeeBand(mi("40"), mi("80"), 1299)), 0, 0, 0, null, 3, NOW, "staff-1", "Texas rates");
        DeliveryFeeRule pickup = new DeliveryFeeRule(PICKUP_ID, FeeMethod.PICKUP, "USD", List.of(), 100, 0, 0, null, 2,
                NOW, "staff-1", "Small pickup fee");
        DeliveryFeeRule carrier = new DeliveryFeeRule(CARRIER_ID, FeeMethod.CARRIER, "USD", List.of(), 0, 200, 800,
                7500L, 4, NOW, "staff-1", "Carrier handling");
        return new DeliveryFees(List.of(door, pickup, carrier), distances);
    }

    private static PreorderWindow window(Money doorOverride, Money pickupOverride) {
        WindowDetails d = Preorders.lubbock();
        return Preorders.published(new WindowDetails(d.name(), d.targets(), d.opensAt(), d.closesAt(), d.deliversOn(),
                d.timeZone(), d.ribbon(), d.headline(), d.smsCopy(), d.categories(), true, true, null, null, true,
                null, d.comms(), doorOverride, pickupOverride, null));
    }

    @Test
    void theDoorFeeIsTheBandOfTheWindowDistanceWithTheRuleVersionAndBand() {
        PreorderWindow w = window(null, null);
        FeeSnapshot near = fees(x -> OptionalDouble.of(4.2)).door(w).orElseThrow();
        assertThat(near.amountMinor()).isEqualTo(599);
        assertThat(near.method()).isEqualTo(FeeMethod.AFROTRANSACT_DELIVERY);
        assertThat(near.ruleId()).isEqualTo(DOOR_ID);
        assertThat(near.ruleVersion()).isEqualTo(3);
        assertThat(near.band()).isEqualTo("0–10 mi");
        assertThat(near.source()).isEqualTo(FeeSource.RULE);
        assertThat(near.distanceMiles()).isEqualByComparingTo("4.2");

        FeeSnapshot far = fees(x -> OptionalDouble.of(61.0)).door(w).orElseThrow();
        assertThat(far.amountMinor()).isEqualTo(1299);
        assertThat(far.band()).isEqualTo("40–80 mi");
    }

    @Test
    void bandsAreInclusiveAtTheStartAndExclusiveAtTheEnd() {
        DeliveryFees f = fees(WindowDistances.UNKNOWN);
        assertThat(f.door("USD", mi("0")).orElseThrow().amountMinor()).isEqualTo(599);
        assertThat(f.door("USD", mi("9.99")).orElseThrow().amountMinor()).isEqualTo(599);
        assertThat(f.door("USD", mi("10")).orElseThrow().amountMinor()).isEqualTo(799);
        assertThat(f.door("USD", mi("79.99")).orElseThrow().amountMinor()).isEqualTo(1299);
    }

    @Test
    void beyondTheLastBandAfroTransactDoesNotDeliverAndTheOrderShipsByCarrier() {
        DeliveryFees f = fees(x -> OptionalDouble.of(80.0));
        assertThat(f.door(window(null, null))).as("exactly at the end is outside").isEmpty();
        assertThat(f.door("USD", mi("150"))).isEmpty();
        assertThat(f.door("CAD", mi("5"))).as("no rule in that currency").isEmpty();
        assertThat(f.rule(FeeMethod.AFROTRANSACT_DELIVERY, "USD").orElseThrow().coverageMiles()).isEqualByComparingTo("80");
    }

    @Test
    void anUnmeasuredWindowPaysTheFarthestBandSoAFeeIsNeverUnderstated() {
        FeeSnapshot s = fees(WindowDistances.UNKNOWN).door(window(null, null)).orElseThrow();
        assertThat(s.amountMinor()).isEqualTo(1299);
        assertThat(s.distanceMiles()).isNull();
    }

    @Test
    void aFeeStaffSetOnTheWindowWinsAndStillNamesTheRule() {
        PreorderWindow w = window(new Money(450, "USD"), new Money(0, "USD"));
        DeliveryFees f = fees(x -> OptionalDouble.of(95.0));
        FeeSnapshot door = f.door(w).orElseThrow();
        assertThat(door.amountMinor()).isEqualTo(450);
        assertThat(door.source()).isEqualTo(FeeSource.WINDOW_OVERRIDE);
        assertThat(door.ruleId()).as("the rule it overrides").isEqualTo(DOOR_ID);
        assertThat(door.band()).isNull();
        FeeSnapshot pickup = f.pickup(w);
        assertThat(pickup.amountMinor()).as("an explicit zero is a free pickup, not 'use the rule'").isZero();
        assertThat(pickup.source()).isEqualTo(FeeSource.WINDOW_OVERRIDE);
        assertThat(pickup.ruleId()).isEqualTo(PICKUP_ID);
    }

    @Test
    void pickupIsTheRuleFeeAndFreeWhenStaffSetNone() {
        PreorderWindow w = window(null, null);
        FeeSnapshot rule = fees(WindowDistances.UNKNOWN).pickup(w);
        assertThat(rule.amountMinor()).isEqualTo(100);
        assertThat(rule.ruleVersion()).isEqualTo(2);
        assertThat(rule.source()).isEqualTo(FeeSource.RULE);
        FeeSnapshot none = new DeliveryFees(List.of(), WindowDistances.UNKNOWN).pickup(w);
        assertThat(none.amountMinor()).isZero();
        assertThat(none.ruleId()).isNull();
    }

    @Test
    void carrierShippingIsTheQuotePlusHandlingElseTheFallbackAndFreeOverTheThreshold() {
        DeliveryFees f = fees(WindowDistances.UNKNOWN);
        FeeSnapshot quoted = f.carrier("USD", 1150L, 2000).orElseThrow();
        assertThat(quoted.amountMinor()).isEqualTo(1350);
        assertThat(quoted.source()).isEqualTo(FeeSource.CARRIER_QUOTE);
        assertThat(quoted.ruleVersion()).isEqualTo(4);
        FeeSnapshot fallback = f.carrier("USD", null, 2000).orElseThrow();
        assertThat(fallback.amountMinor()).isEqualTo(800);
        assertThat(fallback.source()).isEqualTo(FeeSource.CARRIER_FALLBACK);
        FeeSnapshot free = f.carrier("USD", 1150L, 7500).orElseThrow();
        assertThat(free.amountMinor()).isZero();
        assertThat(free.band()).isEqualTo("free over 75.00");
        assertThat(f.carrier("CAD", null, 100)).as("no carrier rule: orders keeps its own flat rate").isEmpty();
    }

    @Test
    void aDoorWindowIsPublishableOnlyWithARuleOrItsOwnFee() {
        PreorderWindow w = window(null, null);
        assertThat(w.publishChecks(fees(WindowDistances.UNKNOWN))).isEmpty();
        DeliveryFees noRules = new DeliveryFees(List.of(), WindowDistances.UNKNOWN);
        assertThat(w.publishChecks(noRules)).extracting(RuleViolation::code)
                .containsExactly("delivery-runs.preorder-fee-missing");
        assertThat(window(new Money(300, "USD"), null).publishChecks(noRules)).isEmpty();
    }

    @Test
    void aDoorOnlyWindowBeyondTheLastBandIsNotOfferedAndRoutesToCarrier() {
        PreorderWindow far = Preorders.published(doorOnly());
        Coverage c = new Coverage(new PreorderSettings(true, Fallback.SHIP, EnumSet.allOf(Placement.class),
                PaymentSettings.DEFAULT, null, 0), List.of(new CoverageArea(AreaKey.parse("US/TX/Lubbock"), true)),
                fees(x -> OptionalDouble.of(140.0)));
        Resolution r = new PreorderResolver().resolve("US", "TX", "Lubbock", List.of(far), c, Map.of(), false, NOW);
        assertThat(r.on()).isFalse();
        assertThat(r.beyondRange()).isTrue();
        assertThat(r.doorFee()).isNull();
        assertThat(r.fallback()).isEqualTo(Fallback.SHIP);

        Coverage near = new Coverage(c.settings(), c.areas(), fees(x -> OptionalDouble.of(25.0)));
        Resolution ok = new PreorderResolver().resolve("US", "TX", "Lubbock", List.of(far), near, Map.of(), false, NOW);
        assertThat(ok.on()).isTrue();
        assertThat(ok.beyondRange()).isFalse();
        assertThat(ok.doorFee().amountMinor()).isEqualTo(799);
        assertThat(ok.doorFee().ruleVersion()).isEqualTo(3);
    }

    @Test
    void aWindowWithPickupStaysOnBeyondTheLastBandButOffersNoDoor() {
        PreorderWindow both = window(null, null);
        Coverage c = new Coverage(new PreorderSettings(true, Fallback.SHIP, EnumSet.allOf(Placement.class),
                PaymentSettings.DEFAULT, null, 0), List.of(new CoverageArea(AreaKey.parse("US/TX/Lubbock"), true)),
                fees(x -> OptionalDouble.of(140.0)));
        Resolution r = new PreorderResolver().resolve("US", "TX", "Lubbock", List.of(both), c, Map.of(), false, NOW);
        assertThat(r.on()).isTrue();
        assertThat(r.doorFee()).isNull();
        assertThat(r.pickupFee().amountMinor()).isEqualTo(100);
    }

    private static WindowDetails doorOnly() {
        WindowDetails d = Preorders.lubbock();
        return new WindowDetails(d.name(), d.targets(), NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(3)),
                d.deliversOn(), d.timeZone(), d.ribbon(), d.headline(), d.smsCopy(), d.categories(), true, false,
                null, null, true, null, d.comms(), null, null, null);
    }

    // ---- rule validation ---------------------------------------------------------------------------------------

    private static void invalidBands(List<FeeBand> bands, String message) {
        assertThatThrownBy(() -> new DeliveryFeeRule(UUID.randomUUID(), FeeMethod.AFROTRANSACT_DELIVERY, "USD", bands,
                0, 0, 0, null, 1, NOW, "s", "r")).isInstanceOf(RuleViolation.class)
                .hasFieldOrPropertyWithValue("code", "delivery-runs.invalid-fee-band").hasMessageContaining(message);
    }

    @Test
    void distanceBandsStartAtZeroAndNeitherOverlapNorLeaveAGap() {
        invalidBands(List.of(), "between 1 and");
        invalidBands(List.of(new FeeBand(mi("5"), mi("10"), 599)), "starts at 0");
        invalidBands(List.of(new FeeBand(mi("0"), mi("10"), 599), new FeeBand(mi("12"), mi("20"), 799)),
                "next one starts at 10");
        invalidBands(List.of(new FeeBand(mi("0"), mi("10"), 599), new FeeBand(mi("8"), mi("20"), 799)),
                "next one starts at 10");
        assertThatThrownBy(() -> new FeeBand(mi("10"), mi("10"), 599)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> new FeeBand(mi("0"), mi("501"), 599)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> new FeeBand(mi("0"), mi("10"), 10_001)).isInstanceOf(RuleViolation.class)
                .hasMessageContaining("between 0 and 100.00");
    }

    @Test
    void onlyAfroTransactDeliveryHasBandsAndFeesStayWithin100() {
        assertThatThrownBy(() -> new DeliveryFeeRule(UUID.randomUUID(), FeeMethod.PICKUP, "USD",
                List.of(new FeeBand(mi("0"), mi("10"), 0)), 0, 0, 0, null, 1, NOW, "s", "r"))
                .isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> new DeliveryFeeRule(UUID.randomUUID(), FeeMethod.CARRIER, "USD", List.of(), 0, 0,
                10_001, null, 1, NOW, "s", "r")).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> new DeliveryFeeRule(UUID.randomUUID(), FeeMethod.PICKUP, "usd", List.of(), 0, 0, 0,
                null, 1, NOW, "s", "r")).isInstanceOf(RuleViolation.class);
        assertThat(new DeliveryFeeRule(UUID.randomUUID(), FeeMethod.PICKUP, "USD", List.of(), 0, 0, 0, null, 1, NOW,
                "s", "r").feeMinor()).as("pickup is often free").isZero();
    }

    @Test
    void nextBumpsTheVersionAndSameNumbersIgnoresBandOrder() {
        DeliveryFeeRule r = fees(WindowDistances.UNKNOWN).rule(FeeMethod.AFROTRANSACT_DELIVERY, "USD").orElseThrow();
        assertThat(r.sameNumbers(List.of(new FeeBand(mi("40"), mi("80"), 1299), new FeeBand(mi("0"), mi("10"), 599),
                new FeeBand(mi("10"), mi("40"), 799)), 0, 0, 0, null)).isTrue();
        assertThat(r.sameNumbers(List.of(new FeeBand(mi("0"), mi("80"), 599)), 0, 0, 0, null)).isFalse();
        DeliveryFeeRule next = r.next(List.of(new FeeBand(mi("0"), mi("80"), 999)), 0, 0, 0, null, "staff-2", "Flat",
                NOW);
        assertThat(next.version()).isEqualTo(4);
        assertThat(next.id()).isEqualTo(r.id());
        assertThat(next.updatedBy()).isEqualTo("staff-2");
    }
}
