package com.afrotransact.bookings.application;

/** Event types published by bookings-service (contracts/asyncapi/fragments/bookings.yaml). */
public final class BookingsEvents {

    /** Aggregate type: every event goes to topic {@code at.bookings.events}. */
    public static final String AGGREGATE = "bookings";

    /** Deposit authorised; waiting for the provider (request-to-book). */
    public static final String REQUESTED = "booking.requested";
    /** Confirmed by the provider, automatically (instant booking) or after a new time was accepted. */
    public static final String CONFIRMED = "booking.confirmed";
    public static final String DECLINED = "booking.declined";
    /** Unanswered request or unpaid hold expired; the authorisation was released. */
    public static final String EXPIRED = "booking.expired";
    public static final String CANCELLED = "booking.cancelled";
    public static final String RESCHEDULE_REQUESTED = "booking.reschedule_requested";
    public static final String TIME_PROPOSED = "booking.time_proposed";
    public static final String RESCHEDULED = "booking.rescheduled";
    public static final String COMPLETED = "booking.completed";
    public static final String NO_SHOW = "booking.no_show";
    /** A service was created or changed (key = service id) — search indexing. */
    public static final String SERVICE_UPSERTED = "booking.service.upserted";

    private BookingsEvents() {
    }
}
