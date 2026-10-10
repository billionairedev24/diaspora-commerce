package com.afrotransact.bookings.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.bookings.domain.Review;

/**
 * Payload of {@code booking.review.*} (contracts/asyncapi/fragments/bookings.yaml BookingReview). Carries what a
 * moderation queue needs to decide without calling back: the text, the rating, who wrote it and for whom, plus the
 * decision endpoints.
 *
 * @param contentType   always {@code BOOKING_REVIEW} (lets a generic moderation queue route it)
 * @param reviewId      the review id, which is the booking id (one review per booking)
 * @param approvePath   bookings endpoint that publishes it ({@code bookings:reviews:moderate})
 * @param rejectPath    bookings endpoint that removes it, body {@code {reasonCode, note}}
 */
public record ReviewEventPayload(String contentType, UUID reviewId, UUID bookingId, String orgId, UUID serviceId,
                                 String serviceName, String customerId, String reviewerName, int rating, String text,
                                 String status, Instant createdAt, String moderatedBy, Instant moderatedAt,
                                 String reasonCode, String moderationNote, String replyText, Instant repliedAt,
                                 String approvePath, String rejectPath) {

    static ReviewEventPayload of(Review r) {
        Review.Moderation m = r.moderation();
        Review.ProviderReply reply = r.reply();
        String base = "/v1/reviews/" + r.bookingId();
        return new ReviewEventPayload("BOOKING_REVIEW", r.bookingId(), r.bookingId(), r.orgId(), r.serviceId(),
                r.serviceName(), r.customerId(), r.reviewerName(), r.rating(), r.text(), r.status().name(),
                r.createdAt(), m == null ? null : m.by(), m == null ? null : m.at(),
                m == null || m.reason() == null ? null : m.reason().name(), m == null ? null : m.note(),
                reply == null ? null : reply.text(), reply == null ? null : reply.at(), base + "/approve",
                base + "/reject");
    }
}
