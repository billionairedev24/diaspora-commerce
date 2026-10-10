package com.afrotransact.bookings.application;

import java.util.UUID;

import com.afrotransact.bookings.domain.Review;

/** Ratings: the customer of a completed booking rates it once, 1–5 stars with an optional text. */
public interface ReviewService {

    /**
     * Rates the booking.
     *
     * @throws com.afrotransact.bookings.domain.BookingsStateConflict {@code bookings.not-reviewable} unless completed,
     *                                                               {@code bookings.already-reviewed} the second time
     */
    Review rate(UUID bookingId, int rating, String text);
}
