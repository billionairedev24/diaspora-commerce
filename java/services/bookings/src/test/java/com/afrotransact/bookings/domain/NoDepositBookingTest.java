package com.afrotransact.bookings.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** S232: services without a deposit charge the full price off-session when the appointment is far away. */
class NoDepositBookingTest {

    private static final Instant NOW = Fixtures.NOW;
    private static final Instant FAR = NOW.plus(Duration.ofDays(30));
    private static final BalanceChargePolicy POLICY = new BalanceChargePolicy(Duration.ofDays(5), Duration.ofDays(2),
            3, Duration.ofHours(6));
    private static final MoneyMove RELEASE = new MoneyMove(MoneyKind.RELEASE, null, 0, false);

    @Test
    void confirmationCapturesNothingAndAFarAppointmentReleasesTheWholeHold() {
        Booking b = Fixtures.confirmedNoDeposit(CancellationPolicy.moderate(), FAR);
        assertThat(b.depositCapturedMinor()).isZero();
        assertThat(b.releaseBalanceAhead(POLICY, NOW.plusSeconds(61))).containsExactly(RELEASE);
        assertThat(b.balance().state()).isEqualTo(BalanceState.DUE);
        assertThat(b.paymentState()).isEqualTo(PaymentState.RELEASED);
        assertThat(b.releasedMinor()).isEqualTo(12_000);
        assertThat(b.authorisationReleasedAhead()).isTrue();
        assertThat(b.releaseBalanceAhead(POLICY, NOW.plusSeconds(62))).as("only once").isEmpty();
        assertThat(POLICY.decide(b, FAR.minus(Duration.ofDays(1)))).isEqualTo(BalanceChargePolicy.Decision.CHARGE);
    }

    @Test
    void nearAppointmentsKeepTheAuthorisation() {
        Booking b = Fixtures.confirmedNoDeposit(CancellationPolicy.moderate(), NOW.plus(Duration.ofDays(2)));
        assertThat(b.releaseBalanceAhead(POLICY, NOW.plusSeconds(61))).isEmpty();
        assertThat(b.balance().state()).isEqualTo(BalanceState.ON_AUTHORISATION);
        assertThat(b.authorisationReleasedAhead()).isFalse();
    }

    @Test
    void theAuthorisedOffSessionChargeIsCapturedOnCompletion() {
        Booking b = Fixtures.confirmedNoDeposit(CancellationPolicy.moderate(), FAR);
        b.releaseBalanceAhead(POLICY, NOW.plusSeconds(61));
        UUID charge = UUID.randomUUID();
        b.balanceChargeSubmitted(1, charge, "AUTHORIZED", null, null, POLICY, FAR.minus(Duration.ofDays(2)));

        Transition done = b.complete(Party.PROVIDER, FAR.plus(Duration.ofHours(3)));
        assertThat(done.moves()).containsExactly(new MoneyMove(MoneyKind.CAPTURE, MoneyMove.BALANCE, 12_000, true),
                new MoneyMove(MoneyKind.SETTLE, null, 0, false));
        assertThat(b.paymentState()).isEqualTo(PaymentState.CAPTURED);
    }

    @Test
    void cancellingCancelsTheOffSessionChargeNotTheReleasedIntent() {
        Booking b = Fixtures.confirmedNoDeposit(CancellationPolicy.moderate(), FAR);
        b.releaseBalanceAhead(POLICY, NOW.plusSeconds(61));
        b.balanceChargeSubmitted(1, UUID.randomUUID(), "AUTHORIZED", null, null, POLICY,
                FAR.minus(Duration.ofDays(2)));

        Booking.Cancellation c = b.cancel(Party.CUSTOMER, "Travelling", FAR.minus(Duration.ofDays(1)), 1_500);
        assertThat(c.transition().moves()).containsExactly(new MoneyMove(MoneyKind.RELEASE, MoneyMove.BALANCE, 0,
                false));
        assertThat(b.balance().state()).isEqualTo(BalanceState.RELEASED);
    }

    @Test
    void cancellingBeforeTheChargeMovesNoMoney() {
        Booking b = Fixtures.confirmedNoDeposit(CancellationPolicy.moderate(), FAR);
        b.releaseBalanceAhead(POLICY, NOW.plusSeconds(61));
        assertThat(b.cancel(Party.CUSTOMER, null, NOW.plus(Duration.ofDays(3)), 1_500).transition().moves())
                .isEmpty();
    }

    @Test
    void declinedChargesFlagTheBookingForTheUnpaidBalanceEvent() {
        Booking b = Fixtures.confirmedNoDeposit(CancellationPolicy.moderate(), FAR);
        b.releaseBalanceAhead(POLICY, NOW.plusSeconds(61));
        Instant at = FAR.minus(Duration.ofDays(2));
        b.balanceChargeSubmitted(1, UUID.randomUUID(), "FAILED", null, "card_declined", POLICY, at);
        b.balanceChargeSubmitted(2, UUID.randomUUID(), "FAILED", null, "card_declined", POLICY, at.plusSeconds(1));
        Transition last = b.balanceChargeSubmitted(3, UUID.randomUUID(), "FAILED", null, "card_declined", POLICY,
                at.plusSeconds(2));
        assertThat(last.balanceUnpaid()).isTrue();
        assertThat(b.balance().state()).isEqualTo(BalanceState.NEEDS_ATTENTION);
    }

    @Test
    void nearNoShowWithoutFullPriceReleasesTheHold() {
        Booking b = Fixtures.confirmedNoDeposit(CancellationPolicy.moderate(), NOW.plus(Duration.ofDays(2)));
        Transition t = b.markNoShow(NOW.plus(Duration.ofDays(2)).plusSeconds(60));
        assertThat(t.moves()).containsExactly(RELEASE);
        assertThat(b.paymentState()).isEqualTo(PaymentState.RELEASED);
    }
}
