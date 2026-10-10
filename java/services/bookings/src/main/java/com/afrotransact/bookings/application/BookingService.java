package com.afrotransact.bookings.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.bookings.domain.Activity;
import com.afrotransact.bookings.domain.BalanceState;
import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.BookingStatus;
import com.afrotransact.bookings.domain.CancellationQuote;
import com.afrotransact.bookings.domain.CustomerContact;
import com.afrotransact.bookings.domain.ServiceMode;

/**
 * The booking flow: hold a slot and authorise the deposit, provider confirms / declines / proposes a time,
 * reschedules, cancellations with policy refunds, completion and no-shows. Every write takes an Idempotency-Key.
 */
public interface BookingService {

    /** Customer: holds the slot (no double booking) and creates the deposit PaymentIntent. */
    BookingResult create(CreateBooking command, String idempotencyKey);

    Booking get(UUID id);

    List<Activity> history(UUID id);

    /** The signed-in customer's bookings, active first. */
    List<Booking> mine();

    /**
     * Re-reads the PaymentIntent from payments (the customer just confirmed the card) and applies it; also the
     * off-session balance charge while it waits for the customer's authentication.
     */
    Booking syncPayment(UUID id);

    /**
     * The balance of a long-dated booking and its off-session charge. The customer gets the charge's client secret
     * while their bank asks them to authenticate (Stripe.js / PaymentSheet), then calls {@link #syncPayment}.
     */
    BalancePayment balancePayment(UUID id);

    /** What cancelling now refunds, keeps or releases, for whoever is asking. */
    CancellationQuote cancellationQuote(UUID id);

    Cancelled cancel(UUID id, String reason, String idempotencyKey);

    /** Customer moves the appointment (policy window; provider decides unless auto-accept). */
    Booking reschedule(UUID id, Instant newStart, String idempotencyKey);

    /** Customer accepts the provider's proposal, or the provider accepts the customer's requested time. */
    Booking acceptNewTime(UUID id, String idempotencyKey);

    Booking declineNewTime(UUID id, String idempotencyKey);

    Booking confirm(UUID id, String idempotencyKey);

    Booking decline(UUID id, String reason, String idempotencyKey);

    Booking proposeTime(UUID id, Instant newStart, String idempotencyKey);

    Booking complete(UUID id, String idempotencyKey);

    Booking noShow(UUID id, String idempotencyKey);

    /** Bookings of the organisation (practitioners: their own). */
    List<Booking> forProvider(String orgId, Instant from, Instant to, UUID memberId, BookingStatus status);

    EarningsView earnings(String orgId, Instant from, Instant to);

    /**
     * New booking request.
     *
     * @param memberId preferred team member (null = anyone free at that time)
     * @param channel  web or app
     */
    record CreateBooking(UUID serviceId, UUID memberId, Instant startAt, ServiceMode mode, List<String> addOnIds,
                         CustomerContact customer, String location, String notes, String channel) {

        public CreateBooking {
            addOnIds = addOnIds == null ? List.of() : List.copyOf(addOnIds);
        }
    }

    /**
     * A created booking.
     *
     * @param replayed the Idempotency-Key was seen before; this is the original booking
     */
    record BookingResult(Booking booking, boolean replayed) {
    }

    /** A cancelled booking and the refund applied. */
    record Cancelled(Booking booking, CancellationQuote quote) {
    }

    /**
     * The balance and its off-session charge.
     *
     * @param clientSecret only for the booking's customer, only while {@link BalanceState#ACTION_REQUIRED}
     */
    record BalancePayment(UUID bookingId, String reference, BalanceState state, UUID paymentId, long amountMinor,
                          String currency, String clientSecret, String failureMessage, int attempts,
                          Instant nextAttemptAt) {
    }
}
