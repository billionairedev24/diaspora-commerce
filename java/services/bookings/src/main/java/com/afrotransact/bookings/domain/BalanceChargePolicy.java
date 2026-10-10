package com.afrotransact.bookings.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * When the balance of a long-dated booking is released and charged off-session.
 *
 * @param authorisationWindow release the balance hold right after the deposit capture when the appointment starts
 *                            more than this far away (card authorisations last about 7 days)
 * @param chargeBefore        charge the balance off-session this long before the appointment
 * @param maxAttempts         declined attempts before the booking is flagged for attention
 * @param retryDelay          wait between declined attempts
 */
public record BalanceChargePolicy(Duration authorisationWindow, Duration chargeBefore, int maxAttempts,
                                  Duration retryDelay) {

    public BalanceChargePolicy {
        Objects.requireNonNull(authorisationWindow, "authorisationWindow");
        Objects.requireNonNull(chargeBefore, "chargeBefore");
        Objects.requireNonNull(retryDelay, "retryDelay");
        if (authorisationWindow.isNegative() || chargeBefore.isNegative() || retryDelay.isNegative()) {
            throw new IllegalArgumentException("Durations must not be negative");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
    }

    /** What the off-session job does with a booking now. */
    public enum Decision {
        /** Not a booking whose balance is to be charged off-session (any more). */
        SKIP,
        /** Too early: the appointment is more than {@link #chargeBefore} away. */
        NOT_YET,
        /** The last attempt was declined; waiting for {@link #retryDelay}. */
        WAIT_RETRY,
        /** Charge now with attempt {@code attempts + 1}. */
        CHARGE,
        /** The attempt limit is used up: flag for attention. */
        GIVE_UP
    }

    /** The job's decision for {@code booking} at {@code now}. */
    public Decision decide(Booking booking, Instant now) {
        BalanceCharge balance = booking.balance();
        if (!booking.status().isActive() || booking.status() == BookingStatus.PENDING_PAYMENT
                || booking.status() == BookingStatus.REQUESTED || !balance.state().chargeable()
                || booking.service().balanceMinor() <= 0 || booking.paymentId() == null) {
            return Decision.SKIP;
        }
        if (now.isBefore(booking.startAt().minus(chargeBefore))) {
            return Decision.NOT_YET;
        }
        if (balance.attempts() >= maxAttempts) {
            return Decision.GIVE_UP;
        }
        if (balance.state() == BalanceState.FAILED && balance.nextAttemptAt() != null
                && now.isBefore(balance.nextAttemptAt())) {
            return Decision.WAIT_RETRY;
        }
        return Decision.CHARGE;
    }

    /** True when the balance hold must be released right after the deposit capture. */
    public boolean beyondAuthorisation(Instant appointmentStart, Instant now) {
        return Duration.between(now, appointmentStart).compareTo(authorisationWindow) > 0;
    }
}
