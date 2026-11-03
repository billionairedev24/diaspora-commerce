package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Staff conditions on plan charges: start after X, waive while X, waive until a date. */
class ChargeGateTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    private static ChargeGate gate(ChargeGate.Mode mode, RewardMetric metric, Long threshold, Instant until,
                                   Instant metAt) {
        return new ChargeGate(mode, metric, threshold, NOW.minusSeconds(86_400), until, "PROMOTION", null, "staff",
                NOW.minusSeconds(86_400), metAt);
    }

    @Test
    void chargingStartsOnceTheSellerReachesTheSalesThreshold() {
        ChargeGate g = gate(ChargeGate.Mode.START_AFTER, RewardMetric.GROSS_SALES, 100_000L, null, null);
        ChargeGate.Decision below = g.decide(NOW, 42_000);
        assertThat(below.charge()).isFalse();
        assertThat(below.reason()).isEqualTo("Charges start once you reach $1,000 in sales (you're at $420)");
        ChargeGate.Decision reached = g.decide(NOW, 100_000);
        assertThat(reached.charge()).isTrue();
        assertThat(reached.nowMet()).as("the caller records that billing has started").isTrue();
    }

    @Test
    void aMetStartGateStaysMetEvenIfSalesFall() {
        ChargeGate g = gate(ChargeGate.Mode.START_AFTER, RewardMetric.GROSS_SALES, 100_000L, null,
                NOW.minusSeconds(60));
        assertThat(g.decide(NOW, 0).charge()).isTrue();
        assertThat(g.decide(NOW, 0).nowMet()).isFalse();
    }

    @Test
    void feesAreWaivedWhileTheSellerKeepsEnoughListings() {
        ChargeGate g = gate(ChargeGate.Mode.WAIVE_WHILE, RewardMetric.ACTIVE_LISTINGS, 20L, null, null);
        ChargeGate.Decision enough = g.decide(NOW, 24);
        assertThat(enough.charge()).isFalse();
        assertThat(enough.reason()).contains("20 active listings").contains("you have 24");
        assertThat(g.decide(NOW, 19).charge()).as("drops below the threshold: charged again").isTrue();
    }

    @Test
    void aWaiverUntilADateEndsOnThatDate() {
        Instant until = Instant.parse("2027-01-05T00:00:00Z");
        ChargeGate g = gate(ChargeGate.Mode.WAIVE_UNTIL, null, null, until, null);
        assertThat(g.needsMetric()).isFalse();
        ChargeGate.Decision during = g.decide(NOW, 0);
        assertThat(during.charge()).isFalse();
        assertThat(during.reason()).isEqualTo("Free until Jan 5, 2027");
        ChargeGate.Decision after = g.decide(until, 0);
        assertThat(after.charge()).isTrue();
        assertThat(after.expired()).isTrue();
    }
}
