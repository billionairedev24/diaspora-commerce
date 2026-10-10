package com.afrotransact.bookings.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.bookings.domain.RatingSummary;
import com.afrotransact.bookings.domain.Review;
import com.afrotransact.bookings.domain.ReviewStatus;

/** Persistence port for booking reviews (one per booking; a second one is a {@code bookings.already-reviewed}). */
public interface ReviewRepository {

    Review insert(Review review);

    Optional<Review> forBooking(UUID bookingId);

    /** Saves moderation or reply changes if the version still matches; returns the stored copy (version + 1). */
    Review update(Review review);

    /** Average and count of approved reviews per service; services without approved reviews are absent. */
    Map<UUID, RatingSummary> summaries(Collection<UUID> serviceIds);

    /** Average and count of the organisation's approved reviews ({@link RatingSummary#NONE} without any). */
    RatingSummary providerSummary(String orgId);

    /** Approved reviews of one service or one provider (exactly one of the two), newest first. */
    ReviewPage published(String orgId, UUID serviceId, int page, int size);

    /** Reviews in a moderation state across all providers, oldest first (the moderation queue). */
    ReviewPage byStatus(ReviewStatus status, int page, int size);

    /** One provider's reviews in any (or the given) state, newest first. */
    ReviewPage byOrg(String orgId, ReviewStatus status, int page, int size);

    /** One page of reviews. */
    record ReviewPage(List<Review> items, long total, int page, int size) {

        public ReviewPage {
            items = List.copyOf(items);
        }
    }
}
