package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.afrotransact.payments.domain.FeeWaivers.Fee;
import com.afrotransact.payments.domain.FeeWaivers.Kind;
import com.afrotransact.payments.domain.FeeWaivers.Waiver;
import org.junit.jupiter.api.Test;

/** The delivery benefits as ADR 0005 resolves fees: order minimum, per-order cap, which fees are covered. */
class FeeWaiversTest {

    private static final Instant T = Instant.parse("2026-10-28T00:00:00Z");

    private static MembershipTier tier(TierBenefit... benefits) {
        return new MembershipTier("plus", "Plus", "", TierStatus.PUBLISHED, 10, "USD", 999, 9900, 14,
                List.of(benefits), T, "test", T, 0);
    }

    private static TierBenefit delivery(Long min, Long cap) {
        return new TierBenefit(BenefitCode.FREE_DELIVERY, min, cap, null, null, null);
    }

    private static TierBenefit shipping(Long min, Long cap) {
        return new TierBenefit(BenefitCode.FREE_SHIPPING, min, cap, null, null, null);
    }

    @Test
    void feesAreWaivedOnceTheGoodsReachTheMinimum() {
        MembershipTier plus = tier(delivery(2500L, null), shipping(2500L, null));
        List<Fee> fees = List.of(new Fee("s1", Kind.AFROTRANSACT_DELIVERY, 799), new Fee("s2", Kind.CARRIER, 599));

        assertThat(FeeWaivers.resolve(plus, 2499, fees)).as("a cent short of the minimum").isEmpty();
        List<Waiver> waived = FeeWaivers.resolve(plus, 2500, fees);
        assertThat(waived).containsExactly(new Waiver("s1", BenefitCode.FREE_DELIVERY, 799),
                new Waiver("s2", BenefitCode.FREE_SHIPPING, 599));
        assertThat(FeeWaivers.total(waived)).isEqualTo(1398);
    }

    @Test
    void theCapLimitsWhatOneOrderWaivesAcrossItsShipments() {
        MembershipTier capped = tier(delivery(0L, 1000L));
        List<Fee> fees = List.of(new Fee("a", Kind.AFROTRANSACT_DELIVERY, 799), new Fee("b",
                Kind.AFROTRANSACT_DELIVERY, 799));
        List<Waiver> waived = FeeWaivers.resolve(capped, 5000, fees);
        assertThat(waived).containsExactly(new Waiver("a", BenefitCode.FREE_DELIVERY, 799),
                new Waiver("b", BenefitCode.FREE_DELIVERY, 201));
        assertThat(FeeWaivers.total(waived)).isEqualTo(1000);
    }

    @Test
    void deliveryAndShippingAreSeparateBenefitsAndPickupAndExpressAreNeverWaived() {
        MembershipTier deliveryOnly = tier(delivery(0L, null));
        List<Fee> fees = List.of(new Fee("d", Kind.AFROTRANSACT_DELIVERY, 799), new Fee("c", Kind.CARRIER, 599),
                new Fee("p", Kind.PICKUP, 100), new Fee("x", Kind.EXPRESS, 1500));
        assertThat(FeeWaivers.resolve(deliveryOnly, 9000, fees)).containsExactly(new Waiver("d",
                BenefitCode.FREE_DELIVERY, 799));
        assertThat(FeeWaivers.resolve(tier(TierBenefit.flag(BenefitCode.PRIORITY_SUPPORT)), 9000, fees)).isEmpty();
    }

    @Test
    void aFreeFeeHasNothingToWaive() {
        assertThat(FeeWaivers.resolve(tier(delivery(0L, null)), 9000, List.of(new Fee("d",
                Kind.AFROTRANSACT_DELIVERY, 0)))).isEmpty();
    }

    @Test
    void anUpgradeCostsOnlyTheDifferenceForTheTimeLeft() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-01-31T00:00:00Z");
        Instant half = Instant.parse("2026-01-16T00:00:00Z");
        assertThat(Proration.upgradeCharge(1000, 2000, true, start, end, half)).isEqualTo(500);
        assertThat(Proration.upgradeCharge(1000, 2000, true, start, end, start)).isEqualTo(1000);
        assertThat(Proration.upgradeCharge(1000, 2000, true, start, end, end)).isZero();
        // another interval: a new period at the new price less the unused part of the old one
        assertThat(Proration.upgradeCharge(1000, 9900, false, start, end, half)).isEqualTo(9400);
        assertThat(Proration.unused(1000, start, end, half)).isEqualTo(500);
    }
}
