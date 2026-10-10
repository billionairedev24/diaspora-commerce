package com.afrotransact.bookings.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer's rating of a completed booking: 1–5 stars with an optional text, one per booking.
 *
 * @param serviceId the service as booked (ratings are averaged per service)
 */
public record Review(UUID bookingId, String orgId, UUID serviceId, String customerId, int rating, String text,
                     Instant createdAt) {

    public static final int MAX_TEXT = 2000;

    public Review {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (rating < 1 || rating > 5) {
            throw new BookingsRuleViolation("bookings.invalid-rating", "Rate the booking from 1 to 5 stars",
                    Map.of("rating", "Between 1 and 5"));
        }
        text = text == null || text.isBlank() ? null : text.trim();
        if (text != null && text.length() > MAX_TEXT) {
            throw new BookingsRuleViolation("bookings.invalid-rating", "Reviews are at most " + MAX_TEXT
                    + " characters", Map.of("text", "At most " + MAX_TEXT + " characters"));
        }
    }

    /** The customer of a completed booking rates it. */
    public static Review of(Booking booking, int rating, String text, Instant now) {
        if (!booking.reviewable()) {
            throw new BookingsStateConflict("bookings.not-reviewable", "Only completed bookings can be rated");
        }
        return new Review(booking.id(), booking.orgId(), booking.service().serviceId(), booking.customerId(), rating,
                text, now);
    }
}
