package com.afrotransact.bookings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/** Balance release after the deposit capture, off-session balance charges and their outcomes. */
class LongDatedBookingTest {

    private static final Instant NOW = Fixtures.NOW;
    private static final Instant FAR = NOW.plus(Duration.ofDays(30));
    private static final Instant CHARGE_AT = FAR.minus(Duration.ofDays(2));
    private static final BalanceChargePolicy POLICY = new BalanceChargePolicy(Duration.ofDays(7), Duration.ofDays(2),
            2, Duration.ofHours(6));
    private static final MoneyMove CAPTURE_BALANCE = new MoneyMove(MoneyKind.CAPTURE, MoneyMove.BALANCE, 14_000, true);
    private static final MoneyMove SETTLE = new MoneyMove(MoneyKind.SETTLE, null, 0, false);

    private static Booking released() {
        Booking b = Fixtures.confirmedAt(CancellationPolicy.moderate(), FAR);
        b.releaseBalanceAhead(POLICY, NOW.plusSeconds(61));
        return b;
    }

    @Test
    void farAppointmentsReleaseTheBalanceHoldRightAfterTheDepositCapture() {
        Booking b = Fixtures.confirmedAt(CancellationPolicy.moderate(), FAR);
        assertThat(b.releaseBalanceAhead(POLICY, NOW.plusSeconds(61)))
                .containsExactly(new MoneyMove(MoneyKind.RELEASE_REMAINING, null, 0, false));
        assertThat(b.balance().state()).isEqualTo(BalanceState.DUE);
        assertThat(b.releasedMinor()).isEqualTo(14_000);
        assertThat(b.paymentState()).isEqualTo(PaymentState.DEPOSIT_CAPTURED);
        assertThat(b.releaseBalanceAhead(POLICY, NOW.plusSeconds(62))).isEmpty();
    }

    @Test
    void nearAppointmentsAndOpenRequestsKeepTheirAuthorisation() {
        assertThat(Fixtures.confirmed(CancellationPolicy.moderate()).releaseBalanceAhead(POLICY, NOW)).isEmpty();
        Booking request = Fixtures.hold(CancellationPolicy.moderate(), false, FAR);
        request.paymentAuthorized(NOW, Duration.ofHours(4));
        assertThat(request.releaseBalanceAhead(POLICY, NOW)).isEmpty();
    }

    @Test
    void authorisedOffSessionChargeIsCapturedOnCompletion() {
        Booking b = released();
        UUID charge = UUID.randomUUID();
        Transition t = b.balanceChargeSubmitted(1, charge, "AUTHORIZED", null, null, POLICY, CHARGE_AT);
        assertThat(t.moves()).isEmpty();
        assertThat(b.balance().state()).isEqualTo(BalanceState.AUTHORIZED);
        assertThat(b.balance().paymentId()).isEqualTo(charge);

        Transition done = b.complete(Party.PROVIDER, FAR.plus(Duration.ofHours(6)));
        assertThat(done.moves()).containsExactly(CAPTURE_BALANCE, SETTLE);
        assertThat(b.balance().state()).isEqualTo(BalanceState.CAPTURED);
        assertThat(b.balanceCapturedMinor()).isEqualTo(14_000);
        assertThat(b.paymentState()).isEqualTo(PaymentState.CAPTURED);
    }

    @Test
    void requiresActionKeepsTheClientSecretUntilTheCustomerAuthenticates() {
        Booking b = released();
        UUID charge = UUID.randomUUID();
        b.balanceChargeSubmitted(1, charge, "REQUIRES_ACTION", "pi_os_secret", "authentication_required", POLICY,
                CHARGE_AT);
        assertThat(b.balance().state()).isEqualTo(BalanceState.ACTION_REQUIRED);
        assertThat(b.balance().clientSecret()).isEqualTo("pi_os_secret");

        // payments' action_required event carries no secret: nothing changes.
        assertThat(b.balanceChargeUpdated(charge, "REQUIRES_ACTION", null, null, POLICY, CHARGE_AT).changed())
                .isFalse();
        assertThat(b.balance().clientSecret()).isEqualTo("pi_os_secret");
        // An older charge's event is ignored.
        assertThat(b.balanceChargeUpdated(UUID.randomUUID(), "AUTHORIZED", null, null, POLICY, CHARGE_AT).changed())
                .isFalse();

        b.balanceChargeUpdated(charge, "AUTHORIZED", null, null, POLICY, CHARGE_AT.plusSeconds(600));
        assertThat(b.balance().state()).isEqualTo(BalanceState.AUTHORIZED);
        assertThat(b.balance().clientSecret()).isNull();
    }

    @Test
    void customerAuthenticatingAfterCompletionGetsTheBalanceCapturedThen() {
        Booking b = released();
        UUID charge = UUID.randomUUID();
        b.balanceChargeSubmitted(1, charge, "REQUIRES_ACTION", "pi_os_secret", null, POLICY, CHARGE_AT);
        Transition done = b.complete(Party.SYSTEM, FAR.plus(Duration.ofHours(50)));
        assertThat(done.moves()).containsExactly(SETTLE);
        assertThat(b.paymentState()).isEqualTo(PaymentState.DEPOSIT_CAPTURED);

        Transition late = b.balanceChargeUpdated(charge, "AUTHORIZED", null, null, POLICY,
                FAR.plus(Duration.ofHours(60)));
        assertThat(late.moves()).containsExactly(CAPTURE_BALANCE);
        assertThat(b.paymentState()).isEqualTo(PaymentState.CAPTURED);
    }

    @Test
    void declinesAreRetriedThenFlagged() {
        Booking b = released();
        Transition first = b.balanceChargeSubmitted(1, UUID.randomUUID(), "FAILED", null, "card_declined", POLICY,
                CHARGE_AT);
        assertThat(first.balanceUnpaid()).isFalse();
        assertThat(b.balance().state()).isEqualTo(BalanceState.FAILED);
        assertThat(b.balance().nextAttemptAt()).isEqualTo(CHARGE_AT.plus(Duration.ofHours(6)));

        Transition second = b.balanceChargeRefused(2, "payments.no-saved-method", POLICY,
                CHARGE_AT.plus(Duration.ofHours(6)));
        assertThat(second.balanceUnpaid()).isTrue();
        assertThat(second.changed()).isTrue();
        assertThat(b.balance().state()).isEqualTo(BalanceState.NEEDS_ATTENTION);
        assertThat(b.balance().attempts()).isEqualTo(2);
        assertThat(b.balance().failure()).isEqualTo("payments.no-saved-method");
    }

    @Test
    void completingWithoutACollectedBalanceFlagsTheBooking() {
        Booking b = released();
        Transition done = b.complete(Party.PROVIDER, FAR.plus(Duration.ofHours(6)));
        assertThat(done.moves()).containsExactly(SETTLE);
        assertThat(done.balanceUnpaid()).isTrue();
        assertThat(b.balance().state()).isEqualTo(BalanceState.NEEDS_ATTENTION);
        assertThat(b.balanceCapturedMinor()).isZero();
    }

    @Test
    void cancellingCancelsTheOpenOffSessionCharge() {
        Booking b = released();
        b.balanceChargeSubmitted(1, UUID.randomUUID(), "AUTHORIZED", null, null, POLICY, CHARGE_AT);
        Booking.Cancellation c = b.cancel(Party.CUSTOMER, "Plans changed", CHARGE_AT.plusSeconds(60), 1500);
        assertThat(c.transition().moves()).extracting(MoneyMove::kind, MoneyMove::lineId)
                .contains(Tuple.tuple(MoneyKind.RELEASE, MoneyMove.BALANCE));
        assertThat(b.balance().state()).isEqualTo(BalanceState.RELEASED);
        assertThat(b.balanceChargeUpdated(b.balance().paymentId(), "AUTHORIZED", null, null, POLICY,
                CHARGE_AT.plusSeconds(120)).changed()).isFalse();
    }

    @Test
    void cancellingADueBalanceMovesNoMoreMoneyForIt() {
        Booking b = released();
        Booking.Cancellation c = b.cancel(Party.CUSTOMER, null, NOW.plus(Duration.ofDays(1)), 1500);
        assertThat(c.transition().moves()).extracting(MoneyMove::kind).containsExactly(MoneyKind.REFUND);
        assertThat(b.balance().state()).isEqualTo(BalanceState.RELEASED);
        assertThat(b.releasedMinor()).isEqualTo(14_000);
    }

    @Test
    void reviewsOnlyForCompletedBookings() {
        Booking b = Fixtures.confirmed(CancellationPolicy.moderate());
        assertThatThrownBy(() -> Review.of(b, 5, "Great", NOW)).isInstanceOf(BookingsStateConflict.class);
        b.complete(Party.PROVIDER, Fixtures.START.plus(Duration.ofHours(6)));
        Review r = Review.of(b, 4, "  Lovely braids  ", NOW);
        assertThat(r.text()).isEqualTo("Lovely braids");
        assertThat(r.serviceId()).isEqualTo(b.service().serviceId());
        assertThatThrownBy(() -> Review.of(b, 6, null, NOW)).isInstanceOf(BookingsRuleViolation.class);
        assertThatThrownBy(() -> Review.of(b, 0, null, NOW)).isInstanceOf(BookingsRuleViolation.class);
        assertThat(new RatingSummary(13 / 3.0, 3).average()).isEqualTo(4.3);
        assertThat(List.of(RatingSummary.NONE)).extracting(RatingSummary::count).containsExactly(0);
    }
}
