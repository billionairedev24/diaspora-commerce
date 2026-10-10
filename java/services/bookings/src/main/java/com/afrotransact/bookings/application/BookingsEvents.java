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
    /**
     * The balance of a long-dated booking could not be collected off-session (declined after every attempt, or not
     * authorised by completion): provider and customer need to settle it.
     */
    public static final String BALANCE_UNPAID = "booking.balance_unpaid";
    /**
     * A customer rated a completed booking; the review waits for moderation (key = booking id). trust-ops queues it.
     */
    public static final String REVIEW_SUBMITTED = "booking.review.submitted";
    /** Moderation published the review (it now counts in ratings). */
    public static final String REVIEW_APPROVED = "booking.review.approved";
    /** Moderation removed the review (never public, or taken down after publication). */
    public static final String REVIEW_REJECTED = "booking.review.rejected";
    /** The provider replied (once) to a published review. */
    public static final String REVIEW_REPLIED = "booking.review.replied";
    /** A service was created or changed (key = service id) — search indexing. */
    public static final String SERVICE_UPSERTED = "booking.service.upserted";

    private BookingsEvents() {
    }
}
