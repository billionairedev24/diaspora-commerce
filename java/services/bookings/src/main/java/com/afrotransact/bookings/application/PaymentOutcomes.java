package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.domain.Transition;
import com.afrotransact.bookings.port.BookingRepository;
import com.afrotransact.bookings.port.ProviderRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/** Applies a PaymentIntent status (from payments' events or a refresh) to its booking. Caller holds the tx. */
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

    Booking apply(UUID bookingId, String paymentStatus, String failureMessage) {
        Booking b = bookings.lock(bookingId).orElseThrow(() -> new NotFoundException("bookings.booking-not-found",
                "Booking " + bookingId + " not found"));
        Instant now = Instant.now(clock);
        Transition t = switch (paymentStatus == null ? "" : paymentStatus) {
            case "AUTHORIZED", "PARTIALLY_CAPTURED" -> b.paymentAuthorized(now, settings.requestSla());
            case "FAILED" -> b.paymentFailed(failureMessage, now);
            default -> null;
        };
        if (t == null || !t.changed()) {
            return b;
        }
        Provider p = providers.find(b.orgId()).orElseThrow();
        money.execute(b, t.moves(), now);
        Booking saved = bookings.update(b);
        events.publish(t, saved, p);
        return saved;
    }
}
