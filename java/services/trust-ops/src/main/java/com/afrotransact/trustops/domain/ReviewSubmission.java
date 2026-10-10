package com.afrotransact.trustops.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer review of a completed booking waiting for moderation, as announced by bookings-service's
 * {@code booking.review.submitted} (one review per booking, so the booking id is the review id).
 *
 * @param orgId        the provider organisation that was reviewed
 * @param customerId   the author (token subject); never public, shown to moderators for conflict-of-interest checks
 * @param reviewerName the public display name, e.g. "Adaeze N."
 * @param rating       1 to 5 stars
 * @param text         the review text (optional)
 */
public record ReviewSubmission(UUID bookingId, String orgId, UUID serviceId, String serviceName, String customerId,
                               String reviewerName, int rating, String text, Instant submittedAt) {

    public ReviewSubmission {
        Objects.requireNonNull(bookingId, "bookingId");
        if (orgId == null || orgId.isBlank()) {
            throw new IllegalArgumentException("orgId is required");
        }
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("rating must be 1 to 5, was " + rating);
        }
        serviceName = blankToNull(serviceName, 200);
        reviewerName = blankToNull(reviewerName, 120);
        text = blankToNull(text, 2000);
    }

    /** One line for the queue and the audit log, e.g. "★★★★☆ review of Box braids". */
    public String summary() {
        String stars = "★".repeat(rating) + "☆".repeat(5 - rating);
        return stars + " review of " + (serviceName == null ? "a service" : serviceName);
    }

    private static String blankToNull(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
