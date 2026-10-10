package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.bookings.domain.BalanceCharge;
import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.domain.Transition;
import com.afrotransact.bookings.port.BookingRepository;
import com.afrotransact.bookings.port.ProviderRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * Applies a PaymentIntent status (from payments' events or a refresh) to its booking: the original intent (deposit
 * and balance authorisation) or the off-session balance charge. Caller holds the tx.
 */
@Component
class PaymentOutcomes {

    private final BookingRepository bookings;
    private final ProviderRepository providers;
    private final BookingMoney money;
    private final BookingEventPublisher events;
    private final BookingsSettings settings;
    private final Clock clock;

    PaymentOutcomes(BookingRepository bookings, ProviderRepository providers, BookingMoney money,
                    BookingEventPublisher events, BookingsSettings settings, Clock clock) {
        this.bookings = bookings;
        this.providers = providers;
        this.money = money;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    /** Status of the booking's original PaymentIntent. */
    Booking apply(UUID bookingId, String paymentStatus, String failureMessage) {
        Booking b = lock(bookingId);
        Instant now = Instant.now(clock);
        Transition t = switch (paymentStatus == null ? "" : paymentStatus) {
            case "AUTHORIZED", "PARTIALLY_CAPTURED" -> b.paymentAuthorized(now, settings.requestSla());
            case "FAILED" -> b.paymentFailed(failureMessage, now);
            default -> null;
        };
        return save(b, t, t != null && t.changed(), now);
    }

    /** Status of the booking's off-session balance charge {@code chargeId} (statuses of older charges are ignored). */
    Booking applyBalance(UUID bookingId, UUID chargeId, String paymentStatus, String clientSecret,
                         String failureMessage) {
        Booking b = lock(bookingId);
        Instant now = Instant.now(clock);
        BalanceCharge before = b.balance();
        Transition t = b.balanceChargeUpdated(chargeId, paymentStatus, clientSecret, failureMessage,
                settings.balance(), now);
        return save(b, t, t.changed() || !before.equals(b.balance()), now);
    }

    private Booking save(Booking b, Transition t, boolean changed, Instant now) {
        if (!changed) {
            return b;
        }
        Provider p = providers.find(b.orgId()).orElseThrow();
        Transition done = money.apply(b, t, now);
        Booking saved = bookings.update(b);
        events.publish(done, saved, p);
        return saved;
    }

    private Booking lock(UUID bookingId) {
        return bookings.lock(bookingId).orElseThrow(() -> new NotFoundException("bookings.booking-not-found",
                "Booking " + bookingId + " not found"));
    }
}
