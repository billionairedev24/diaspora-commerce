package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** S323: what a membership's early access changes for runs and pre-order windows, and what it leaves alone. */
class EarlyAccessTest {

    private static final Instant NOW = Runs.NOW;

    private static Run announced(Instant opensAt) {
        RunTerms base = Runs.terms(1, Capacity.UNLIMITED, CutoffPolicy.EXTEND);
        RunTerms terms = new RunTerms(base.window(), base.cutoffAt(), opensAt, base.sellerPickupAt(), base.feeMinor(),
                base.currency(), base.minOrders(), base.capacity(), base.dropMode(), base.pickupPoint(),
                base.cutoffPolicy(), base.extendDays());
        return Run.draft(UUID.randomUUID(), UUID.randomUUID(), terms, null, NOW).announce(NOW);
    }

    @Test
    void anAnnouncedRunTakesAMembersOrderWithinTheirEarlyAccessHours() {
        Run run = announced(NOW.plus(Duration.ofHours(10)));
        assertThat(run.acceptsOrders(NOW)).isFalse();
        assertThat(run.acceptsOrdersEarly(NOW, 0)).as("no early access").isFalse();
        assertThat(run.acceptsOrdersEarly(NOW, 9)).as("opens in 10 hours, early access is 9").isFalse();
        assertThat(run.acceptsOrdersEarly(NOW, 10)).as("exactly on the boundary").isTrue();
        assertThat(run.acceptsOrdersEarly(NOW, 48)).isTrue();
        assertThat(run.canTakeEarly(1, NOW, 48)).isTrue();
        assertThat(run.reserve(1, NOW, 48).reservedOrders()).isEqualTo(1);
    }

    @Test
    void earlyAccessNeedsAnOpensAtAndNeverOutlivesTheCutoff() {
        assertThat(announced(null).acceptsOrdersEarly(NOW, 720)).as("no opening time to be early for").isFalse();
        Run run = announced(NOW.plus(Duration.ofHours(10)));
        assertThat(run.acceptsOrdersEarly(run.terms().cutoffAt(), 720)).isFalse();
        Run open = Runs.open(1, Capacity.UNLIMITED, CutoffPolicy.EXTEND);
        assertThat(open.acceptsOrdersEarly(NOW, 48)).as("an open run is not early access").isFalse();
        assertThat(open.canTakeEarly(1, NOW, 48)).as("but a member can of course order from it").isTrue();
    }

    @Test
    void anUpcomingWindowIsOpenForAMemberWhenItOpensWithinTheirHours() {
        Instant opens = NOW.plus(Duration.ofHours(30));
        PreorderWindow upcoming = Preorders.published(Preorders.withDates(Preorders.lubbock(), opens,
                opens.plus(Duration.ofDays(7)), LocalDate.parse("2026-10-27")));
        Coverage coverage = new Coverage(new PreorderSettings(true, Fallback.SHIP, EnumSet.allOf(Placement.class),
                PaymentSettings.DEFAULT, null, 0), List.of(new CoverageArea(AreaKey.parse("US/TX/Lubbock"), true)));
        Resolution r = new PreorderResolver().resolve("US", "TX", "Lubbock", List.of(upcoming), coverage, Map.of(),
                false, NOW);
        assertThat(r.stage()).isEqualTo(PreorderStatus.UPCOMING);

        assertThat(r.forEarlyAccess(NOW, 0).stage()).isEqualTo(PreorderStatus.UPCOMING);
        assertThat(r.forEarlyAccess(NOW, 24).stage()).as("opens in 30 hours, early access 24").isEqualTo(
                PreorderStatus.UPCOMING);
        Resolution early = r.forEarlyAccess(NOW, 48);
        assertThat(early.stage()).isEqualTo(PreorderStatus.OPEN);
        assertThat(early.window()).isEqualTo(upcoming);
        assertThat(early.countdownTarget()).as("a member counts down to the close").isEqualTo(
                upcoming.details().closesAt());
        assertThat(early.on()).isEqualTo(r.on());
    }

    @Test
    void aClosedOrOpenWindowIsLeftAsItIs() {
        PreorderWindow open = Preorders.published(Preorders.withDates(Preorders.lubbock(),
                NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(6)), LocalDate.parse("2026-10-27")));
        Coverage coverage = new Coverage(new PreorderSettings(true, Fallback.SHIP, EnumSet.allOf(Placement.class),
                PaymentSettings.DEFAULT, null, 0), List.of(new CoverageArea(AreaKey.parse("US/TX/Lubbock"), true)));
        Resolution r = new PreorderResolver().resolve("US", "TX", "Lubbock", List.of(open), coverage, Map.of(),
                false, NOW);
        assertThat(r.stage()).isEqualTo(PreorderStatus.OPEN);
        assertThat(r.forEarlyAccess(NOW, 48)).isSameAs(r);
    }
}
