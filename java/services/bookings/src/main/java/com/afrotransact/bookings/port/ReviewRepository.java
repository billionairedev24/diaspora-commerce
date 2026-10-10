package com.afrotransact.bookings.port;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.bookings.domain.RatingSummary;
import com.afrotransact.bookings.domain.Review;

/** Persistence port for booking reviews (one per booking; a second one is a {@code bookings.already-reviewed}). */
public interface ReviewRepository {

    Review insert(Review review);

    Optional<Review> forBooking(UUID bookingId);

    /** Average and count per service; services without reviews are absent. */
    Map<UUID, RatingSummary> summaries(Collection<UUID> serviceIds);
}
