package com.afrotransact.trustops.port;

import java.util.UUID;

import com.afrotransact.trustops.domain.ModerationReasonCode;

/**
 * bookings-service review moderation (contracts/openapi/bookings.yaml), with trust-ops' service token (scope
 * {@code bookings:reviews:moderate}, principal type SERVICE): {@code POST /v1/reviews/{bookingId}/approve} with
 * {@code {"note"}} publishes a review, {@code POST /v1/reviews/{bookingId}/reject} with {@code {"reasonCode", "note"}}
 * removes it. Both are idempotent in bookings.
 */
public interface BookingsReviewClient {

    void approve(UUID bookingId, String note);

    void reject(UUID bookingId, ModerationReasonCode reasonCode, String note);
}
