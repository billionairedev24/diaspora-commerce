package com.afrotransact.bookings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The off-session balance job's decisions (what it does with a booking at a given moment). */
class BalanceChargePolicyTest {

    private static final Instant NOW = Fixtures.NOW;
    private static final Instant FAR = NOW.plus(Duration.ofDays(30));
    private static final BalanceChargePolicy POLICY = new BalanceChargePolicy(Duration.ofDays(7), Duration.ofDays(2),
            3, Duration.ofHours(6));

    /** Confirmed 30 days ahead with the balance hold already released (balance DUE). */
    private static Booking released() {
        Booking b = Fixtures.confirmedAt(CancellationPolicy.moderate(), FAR);
        assertThat(b.releaseBalanceAhead(POLICY, NOW.plusSeconds(61))).hasSize(1);
        return b;
    }

    @Test
    void releasesOnlyBeyondTheAuthorisationWindow() {
        assertThat(POLICY.beyondAuthorisation(NOW.plus(Duration.ofDays(8)), NOW)).isTrue();
        assertThat(POLICY.beyondAuthorisation(NOW.plus(Duration.ofDays(7)), NOW)).isFalse();
        assertThat(POLICY.beyondAuthorisation(NOW.plus(Duration.ofDays(2)), NOW)).isFalse();
    }

    @Test
    void bookingsStillOnTheirAuthorisationAreSkipped() {
        Booking near = Fixtures.confirmed(CancellationPolicy.moderate());
        assertThat(POLICY.decide(near, Fixtures.START.minus(Duration.ofHours(1))))
                .isEqualTo(BalanceChargePolicy.Decision.SKIP);
    }

    @Test
    void waitsUntilChargeBeforeTheAppointment() {
        Booking b = released();
        assertThat(POLICY.decide(b, NOW)).isEqualTo(BalanceChargePolicy.Decision.NOT_YET);
        assertThat(POLICY.decide(b, FAR.minus(Duration.ofDays(2)).minusSeconds(1)))
                .isEqualTo(BalanceChargePolicy.Decision.NOT_YET);
        assertThat(POLICY.decide(b, FAR.minus(Duration.ofDays(2)))).isEqualTo(BalanceChargePolicy.Decision.CHARGE);
        assertThat(POLICY.decide(b, FAR.minus(Duration.ofHours(3)))).isEqualTo(BalanceChargePolicy.Decision.CHARGE);
    }

    @Test
    void retriesADeclineAfterTheDelayWithTheNextAttempt() {
        Booking b = released();
        Instant first = FAR.minus(Duration.ofDays(2));
        b.balanceChargeSubmitted(1, UUID.randomUUID(), "FAILED", null, "card_declined", POLICY, first);
        assertThat(b.balance().state()).isEqualTo(BalanceState.FAILED);
        assertThat(b.balance().attempts()).isEqualTo(1);
        assertThat(POLICY.decide(b, first.plus(Duration.ofHours(5)))).isEqualTo(BalanceChargePolicy.Decision.WAIT_RETRY);
        assertThat(POLICY.decide(b, first.plus(Duration.ofHours(6)))).isEqualTo(BalanceChargePolicy.Decision.CHARGE);
    }

    @Test
    void givesUpOnceTheAttemptLimitIsUsed() {
        Booking b = released();
        Instant at = FAR.minus(Duration.ofDays(2));
        b.balanceChargeSubmitted(1, UUID.randomUUID(), "FAILED", null, "card_declined", POLICY, at);
        BalanceChargePolicy stricter = new BalanceChargePolicy(Duration.ofDays(7), Duration.ofDays(2), 1,
                Duration.ofHours(6));
        assertThat(stricter.decide(b, at.plus(Duration.ofDays(1)))).isEqualTo(BalanceChargePolicy.Decision.GIVE_UP);
        Transition t = b.balanceGiveUp(at.plus(Duration.ofDays(1)));
        assertThat(t.balanceUnpaid()).isTrue();
        assertThat(b.balance().state()).isEqualTo(BalanceState.NEEDS_ATTENTION);
        assertThat(POLICY.decide(b, at.plus(Duration.ofDays(1)))).isEqualTo(BalanceChargePolicy.Decision.SKIP);
    }

    @Test
    void authorisedWaitingOrCancelledBookingsAreSkipped() {
        Booking authorised = released();
        authorised.balanceChargeSubmitted(1, UUID.randomUUID(), "AUTHORIZED", null, null, POLICY,
                FAR.minus(Duration.ofDays(2)));
        assertThat(POLICY.decide(authorised, FAR.minus(Duration.ofDays(1))))
                .isEqualTo(BalanceChargePolicy.Decision.SKIP);

        Booking waiting = released();
        waiting.balanceChargeSubmitted(1, UUID.randomUUID(), "REQUIRES_ACTION", "pi_os_secret",
                "authentication_required", POLICY, FAR.minus(Duration.ofDays(2)));
        assertThat(POLICY.decide(waiting, FAR.minus(Duration.ofDays(1))))
                .isEqualTo(BalanceChargePolicy.Decision.SKIP);

        Booking cancelled = released();
        cancelled.cancel(Party.CUSTOMER, null, FAR.minus(Duration.ofDays(10)), 1500);
        assertThat(POLICY.decide(cancelled, FAR.minus(Duration.ofDays(1))))
                .isEqualTo(BalanceChargePolicy.Decision.SKIP);
    }

    @Test
    void rejectsNonsenseSettings() {
        assertThatThrownBy(() -> new BalanceChargePolicy(Duration.ofDays(7), Duration.ofDays(2), 0,
                Duration.ofHours(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BalanceChargePolicy(Duration.ofDays(-1), Duration.ofDays(2), 3,
                Duration.ofHours(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
