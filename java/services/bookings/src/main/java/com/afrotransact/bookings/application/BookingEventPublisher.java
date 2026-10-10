package com.afrotransact.bookings.application;

import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.BookingStatus;
import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.domain.Transition;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Component;

/** Maps booking transitions to {@code booking.*} events written to the outbox (topic at.bookings.events). */
@Component
class BookingEventPublisher {

    private final DomainEventPublisher events;
    private final BookingsSettings settings;

    BookingEventPublisher(DomainEventPublisher events, BookingsSettings settings) {
        this.events = events;
        this.settings = settings;
    }

    void publish(Transition t, Booking b, Provider p) {
        String type = typeOf(t);
        if (type != null) {
            events.publish(type, BookingsEvents.AGGREGATE, b.id().toString(),
                    BookingEventPayload.of(b, p, t.from().name(), settings.manageUrlBase()));
        }
        if (t.balanceUnpaid()) {
            events.publish(BookingsEvents.BALANCE_UNPAID, BookingsEvents.AGGREGATE, b.id().toString(),
                    BookingEventPayload.of(b, p, t.from().name(), settings.manageUrlBase()));
        }
    }

    static String typeOf(Transition t) {
        BookingStatus to = t.to();
        if (t.from() == to) {
            return t.rescheduled() ? BookingsEvents.RESCHEDULED : null;
        }
        return switch (to) {
            case REQUESTED -> t.from() == BookingStatus.PENDING_PAYMENT ? BookingsEvents.REQUESTED : null;
            case CONFIRMED -> t.rescheduled() ? BookingsEvents.RESCHEDULED
                    : t.from() == BookingStatus.RESCHEDULE_REQUESTED ? null : BookingsEvents.CONFIRMED;
            case DECLINED -> BookingsEvents.DECLINED;
            case EXPIRED -> BookingsEvents.EXPIRED;
            case CANCELLED_CUSTOMER, CANCELLED_PROVIDER -> BookingsEvents.CANCELLED;
            case RESCHEDULE_REQUESTED -> BookingsEvents.RESCHEDULE_REQUESTED;
            case RESCHEDULE_PROPOSED -> BookingsEvents.TIME_PROPOSED;
            case COMPLETED -> BookingsEvents.COMPLETED;
            case NO_SHOW -> BookingsEvents.NO_SHOW;
            case PENDING_PAYMENT -> null;
        };
    }
}
