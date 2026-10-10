package com.afrotransact.bookings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class BookingTest {

    private static final Instant NOW = Fixtures.NOW;

    @Test
    void requestFlowHoldsThenCapturesDepositThenBalance() {
        Booking b = Fixtures.hold(CancellationPolicy.moderate(), false);
        assertThat(b.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(b.blockedUntil()).isEqualTo(b.endAt().plus(Duration.ofMinutes(30)));

        Transition authorised = b.paymentAuthorized(NOW, Duration.ofHours(4));
        assertThat(authorised.to()).isEqualTo(BookingStatus.REQUESTED);
        assertThat(authorised.moves()).isEmpty();
        assertThat(b.requestExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(4)));
        assertThat(b.paymentAuthorized(NOW, Duration.ofHours(4)).changed()).isFalse();

        Transition confirmed = b.confirm(NOW);
        assertThat(confirmed.to()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmed.moves()).containsExactly(new MoneyMove(MoneyKind.CAPTURE, MoneyMove.DEPOSIT, 4_000,
                false));
        assertThat(b.paymentState()).isEqualTo(PaymentState.DEPOSIT_CAPTURED);

        Transition done = b.complete(Party.PROVIDER, Fixtures.START.plus(Duration.ofHours(6)));
        assertThat(done.to()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(done.moves()).containsExactly(new MoneyMove(MoneyKind.CAPTURE, MoneyMove.BALANCE, 14_000, true),
                new MoneyMove(MoneyKind.SETTLE, null, 0, false));
        assertThat(b.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(b.drainActivities()).extracting(Activity::action)
                .contains("Confirmed · deposit captured", "Balance $140.00 charged", "Marked complete");
    }

    @Test
    void instantBookingConfirmsAndCapturesOnAuthorisation() {
        Booking b = Fixtures.hold(CancellationPolicy.flexible(), true);
        Transition t = b.paymentAuthorized(NOW, Duration.ofHours(4));
        assertThat(t.from()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(t.to()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(t.moves()).extracting(MoneyMove::kind).containsExactly(MoneyKind.CAPTURE);
    }

    @Test
    void onlyRequestsCanBeConfirmedOrDeclined() {
        Booking b = Fixtures.confirmed(CancellationPolicy.moderate());
        assertThatThrownBy(() -> b.confirm(NOW)).isInstanceOf(BookingsStateConflict.class);
        assertThatThrownBy(() -> b.decline("busy", NOW)).isInstanceOf(BookingsStateConflict.class);
    }

    @Test
    void declineReleasesTheDepositHold() {
        Booking b = Fixtures.requested(CancellationPolicy.moderate());
        Transition t = b.decline("Fully booked that week", NOW);
        assertThat(t.to()).isEqualTo(BookingStatus.DECLINED);
        assertThat(t.moves()).extracting(MoneyMove::kind).containsExactly(MoneyKind.RELEASE);
        assertThat(b.paymentState()).isEqualTo(PaymentState.RELEASED);
        assertThat(b.releasedMinor()).isEqualTo(18_000);
        assertThat(b.reason()).isEqualTo("Fully booked that week");
    }

    @Test
    void unansweredRequestsExpireAfterTheSla() {
        Booking b = Fixtures.requested(CancellationPolicy.moderate());
        assertThatThrownBy(() -> b.expireRequest(NOW.plus(Duration.ofHours(3))))
                .isInstanceOf(BookingsStateConflict.class);
        Transition t = b.expireRequest(NOW.plus(Duration.ofHours(4)));
        assertThat(t.to()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(t.moves()).extracting(MoneyMove::kind).containsExactly(MoneyKind.RELEASE);
    }

    @Test
    void unpaidHoldsExpire() {
        Booking b = Fixtures.hold(CancellationPolicy.moderate(), false);
        Transition t = b.expireHold(NOW.plus(Duration.ofMinutes(15)));
        assertThat(t.to()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(t.moves()).extracting(MoneyMove::kind).containsExactly(MoneyKind.RELEASE);
    }

    @Test
    void customerCancellationFollowsThePolicyTheBookingWasMadeUnder() {
        Booking b = Fixtures.confirmed(CancellationPolicy.moderate());
        Instant thirtyHoursBefore = b.startAt().minus(Duration.ofHours(30));
        CancellationQuote quote = b.quoteCancellation(Party.CUSTOMER, thirtyHoursBefore, 1500);
        assertThat(quote.refundShareBps()).isEqualTo(5_000);
        assertThat(quote.refundMinor()).isEqualTo(2_000);
        assertThat(quote.keptMinor()).isEqualTo(2_000);
        assertThat(quote.releaseAuthorisation()).isFalse();

        Booking.Cancellation c = b.cancel(Party.CUSTOMER, "Travelling", thirtyHoursBefore, 1500);
        assertThat(c.quote()).isEqualTo(quote);
        assertThat(c.transition().to()).isEqualTo(BookingStatus.CANCELLED_CUSTOMER);
        assertThat(c.transition().moves()).containsExactly(new MoneyMove(MoneyKind.REFUND, MoneyMove.DEPOSIT, 2_000,
                false), new MoneyMove(MoneyKind.SETTLE, null, 0, false));
        assertThat(b.refundedMinor()).isEqualTo(2_000);
    }

    @Test
    void cancellingARequestReleasesEverything() {
        Booking b = Fixtures.requested(CancellationPolicy.strict());
        CancellationQuote q = b.quoteCancellation(Party.CUSTOMER, NOW, 1500);
        assertThat(q.releaseAuthorisation()).isTrue();
        assertThat(q.releasedMinor()).isEqualTo(18_000);
        Booking.Cancellation c = b.cancel(Party.CUSTOMER, null, NOW, 1500);
        assertThat(c.transition().moves()).extracting(MoneyMove::kind).containsExactly(MoneyKind.RELEASE);
    }

    @Test
    void lateProviderCancellationRefundsInFullAndCostsAFee() {
        Booking b = Fixtures.confirmed(CancellationPolicy.strict());
        Booking.Cancellation c = b.cancel(Party.PROVIDER, "Sick", b.startAt().minus(Duration.ofHours(5)), 1500);
        assertThat(c.quote().refundMinor()).isEqualTo(4_000);
        assertThat(c.quote().providerFeeMinor()).isEqualTo(1_500);
        assertThat(c.transition().to()).isEqualTo(BookingStatus.CANCELLED_PROVIDER);
        assertThat(b.providerFeeMinor()).isEqualTo(1_500);
        assertThat(c.transition().moves()).extracting(MoneyMove::kind).containsExactly(MoneyKind.REFUND);
    }

    @Test
    void cancelledBookingsCannotBeCancelledAgain() {
        Booking b = Fixtures.requested(CancellationPolicy.moderate());
        b.cancel(Party.CUSTOMER, null, NOW, 1500);
        assertThatThrownBy(() -> b.cancel(Party.CUSTOMER, null, NOW, 1500)).isInstanceOf(BookingsStateConflict.class);
    }

    @Test
    void customerReschedulesInsideTheWindowAndProviderDecides() {
        Booking b = Fixtures.confirmed(CancellationPolicy.moderate());
        Instant later = b.startAt().plus(Duration.ofDays(1));
        Transition asked = b.requestReschedule(later, false, NOW);
        assertThat(asked.to()).isEqualTo(BookingStatus.RESCHEDULE_REQUESTED);
        assertThat(b.proposedStartAt()).isEqualTo(later);
        assertThatThrownBy(() -> b.acceptNewTime(Party.CUSTOMER, NOW)).isInstanceOf(BookingsStateConflict.class);

        Transition accepted = b.acceptNewTime(Party.PROVIDER, NOW);
        assertThat(accepted.to()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(accepted.rescheduled()).isTrue();
        assertThat(b.startAt()).isEqualTo(later);
        assertThat(b.reschedules()).isEqualTo(1);
        assertThat(b.proposedStartAt()).isNull();
    }

    @Test
    void autoAcceptedRescheduleMovesStraightAway() {
        Booking b = Fixtures.confirmed(CancellationPolicy.moderate());
        Instant later = b.startAt().plus(Duration.ofHours(2));
        Transition t = b.requestReschedule(later, true, NOW);
        assertThat(t.rescheduled()).isTrue();
        assertThat(t.to()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.startAt()).isEqualTo(later);
    }

    @Test
    void reschedulingClosesInsideThePolicyWindow() {
        Booking b = Fixtures.confirmed(CancellationPolicy.moderate());
        assertThatThrownBy(() -> b.requestReschedule(b.startAt().plus(Duration.ofDays(1)), true,
                b.startAt().minus(Duration.ofHours(47))))
                .isInstanceOf(BookingsRuleViolation.class)
                .hasMessageContaining("Free changes close 2 days before");
    }

    @Test
    void providerProposalCapturesDepositAndCustomerMayDeclineForAFullRefund() {
        Booking b = Fixtures.requested(CancellationPolicy.moderate());
        Transition proposed = b.proposeTime(b.startAt().plus(Duration.ofDays(2)), NOW);
        assertThat(proposed.to()).isEqualTo(BookingStatus.RESCHEDULE_PROPOSED);
        assertThat(proposed.moves()).extracting(MoneyMove::kind).containsExactly(MoneyKind.CAPTURE);
        assertThat(b.proposedBlockedUntil()).isEqualTo(b.proposedStartAt().plus(Duration.ofMinutes(330)));

        Transition declined = b.declineNewTime(Party.CUSTOMER, NOW);
        assertThat(declined.to()).isEqualTo(BookingStatus.CANCELLED_PROVIDER);
        assertThat(declined.moves()).containsExactly(new MoneyMove(MoneyKind.REFUND, MoneyMove.DEPOSIT, 4_000,
                false));
    }

    @Test
    void providerKeepsOriginalTime() {
        Booking b = Fixtures.confirmed(CancellationPolicy.flexible());
        Instant original = b.startAt();
        b.requestReschedule(original.plus(Duration.ofDays(1)), false, NOW);
        Transition kept = b.declineNewTime(Party.PROVIDER, NOW);
        assertThat(kept.to()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.startAt()).isEqualTo(original);
        assertThat(b.proposedStartAt()).isNull();
    }

    @Test
    void noShowOnlyAfterTheStartAndFullPriceWhenThePolicySaysSo() {
        Booking early = Fixtures.confirmed(CancellationPolicy.moderate());
        assertThatThrownBy(() -> early.markNoShow(NOW)).isInstanceOf(BookingsStateConflict.class);

        Booking depositOnly = Fixtures.confirmed(CancellationPolicy.moderate());
        Transition kept = depositOnly.markNoShow(Fixtures.START.plusSeconds(60));
        assertThat(kept.to()).isEqualTo(BookingStatus.NO_SHOW);
        assertThat(kept.moves()).extracting(MoneyMove::kind).containsExactly(MoneyKind.SETTLE);

        Booking full = Fixtures.confirmed(CancellationPolicy.custom(new CustomPolicySettings(48, false, 0, 0,
                NoShowRule.FULL, 24, 2)));
        Transition charged = full.markNoShow(Fixtures.START.plusSeconds(60));
        assertThat(charged.moves()).containsExactly(new MoneyMove(MoneyKind.CAPTURE, MoneyMove.BALANCE, 14_000,
                true), new MoneyMove(MoneyKind.SETTLE, null, 0, false));
    }

    @Test
    void addOnsAndModesAreSnapshotted() {
        ServiceOffering o = Fixtures.braids();
        BookedService s = BookedService.of(o, o.pick(List.of("hair")), ServiceMode.AT_HOME, "Aminata's Braids", "A");
        assertThat(s.priceMinor()).isEqualTo(20_000);
        assertThat(s.durationMinutes()).isEqualTo(330);
        assertThat(s.balanceMinor()).isEqualTo(16_000);
        assertThatThrownBy(() -> BookedService.of(o, List.of(), ServiceMode.VIRTUAL, "x", "y"))
                .isInstanceOf(BookingsRuleViolation.class);
        assertThatThrownBy(() -> o.pick(List.of("nope"))).isInstanceOf(BookingsRuleViolation.class);
    }
}
