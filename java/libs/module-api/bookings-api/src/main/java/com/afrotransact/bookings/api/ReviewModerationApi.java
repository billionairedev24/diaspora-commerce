package com.afrotransact.bookings.api;

import java.util.UUID;

/**
 * What the trust-ops module asks bookings in-process to act on a booking review ({@code POST /v1/reviews/{bookingId}/approve}
 * and {@code /reject}; the review id is the booking id). Entered under the caller's in-process principal; bookings' own
 * moderation permission check applies and a caller without it is refused with
 * {@code com.afrotransact.module.ModuleApiException} status 403. A reject without a note for reason OTHER is 422
 * {@code bookings.invalid-moderation}.
 */
public interface ReviewModerationApi {

    /** Publishes the review (also reinstates a rejected one); approving an approved review changes nothing. */
    void approve(UUID bookingId, String note);

    /** @param reasonCode the rejection reason's name, as bookings names it */
    void reject(UUID bookingId, String reasonCode, String note);
}
