package com.afrotransact.bookings.application;

import java.util.UUID;

import com.afrotransact.bookings.domain.RatingSummary;
import com.afrotransact.bookings.domain.Review;
import com.afrotransact.bookings.domain.ReviewRejectReason;
import com.afrotransact.bookings.domain.ReviewStatus;
import com.afrotransact.bookings.port.ReviewRepository.ReviewPage;

/**
 * Reviews: the customer of a completed booking rates it once (S221); reviews are moderated before they are public,
 * the provider replies once, and approved reviews are listed per provider and per service (S232).
 */
public interface ReviewService {

    /**
     * Rates the booking; the review is {@code PENDING} and {@code booking.review.submitted} queues it for moderation.
     *
     * @throws com.afrotransact.bookings.domain.BookingsStateConflict {@code bookings.not-reviewable} unless completed,
     *                                                               {@code bookings.already-reviewed} the second time
     */
    Review rate(UUID bookingId, int rating, String text);

    /** The moderation queue (staff / trust-ops with {@code bookings:reviews:moderate}); default PENDING. */
    ReviewPage moderationQueue(ReviewStatus status, int page, int size);

    /** A single review for moderation (staff / trust-ops). */
    Review forModeration(UUID bookingId);

    /** Publishes the review (idempotent). */
    Review approve(UUID bookingId, String note);

    /** Removes the review, also after it was published (idempotent). */
    Review reject(UUID bookingId, ReviewRejectReason reason, String note);

    /** The provider's single reply to a published review (Owner / Manager: {@code bookings:manage}, org-wide). */
    Review reply(String orgId, UUID bookingId, String text);

    /** The provider's own reviews in every state ({@code bookings:read}). */
    ReviewPage providerReviews(String orgId, ReviewStatus status, int page, int size);

    /** Approved reviews of a visible provider, newest first, with the provider's rating. */
    PublicReviews publicForProvider(String orgId, int page, int size);

    /** Approved reviews of an active, visible service, newest first, with the service's rating. */
    PublicReviews publicForService(UUID serviceId, int page, int size);

    /** One public page and the rating it belongs to. */
    record PublicReviews(RatingSummary rating, ReviewPage page) {
    }
}
