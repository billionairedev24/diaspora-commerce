package com.afrotransact.bookings.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer's rating of a completed booking: 1–5 stars with an optional text, one per booking (S221). Reviews are
 * moderated before they are public (S232): they start {@link ReviewStatus#PENDING}, trust-ops (or staff) approves
 * or rejects them, and the provider can reply once to an approved review.
 *
 * @param serviceId    the service as booked (ratings are averaged per service and per provider)
 * @param serviceName  service name as booked (for the moderation queue and public lists)
 * @param reviewerName public display name, first name and last initial ("Adaeze N.")
 * @param moderation   the last moderation decision, null while pending
 * @param reply        the provider's reply, null until they reply
 * @param version      optimistic lock
 */
public record Review(UUID bookingId, String orgId, UUID serviceId, String serviceName, String customerId,
                     String reviewerName, int rating, String text, Instant createdAt, ReviewStatus status,
                     Moderation moderation, ProviderReply reply, long version) {

    public static final int MAX_TEXT = 2000;
    public static final int MAX_REPLY = 1000;
    public static final int MAX_NOTE = 500;

    public Review {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(status, "status");
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

    /**
     * A moderation decision.
     *
     * @param by     staff subject or service client (trust-ops) that decided
     * @param reason why it was rejected (null when approved)
     * @param note   optional note (required for {@link ReviewRejectReason#OTHER})
     */
    public record Moderation(String by, Instant at, ReviewRejectReason reason, String note) {

        public Moderation {
            Objects.requireNonNull(by, "by");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * The provider's single public reply.
     *
     * @param by subject of the provider member who replied
     */
    public record ProviderReply(String text, String by, Instant at) {

        public ProviderReply {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(by, "by");
            Objects.requireNonNull(at, "at");
        }
    }

    /** The customer of a completed booking rates it; the review waits for moderation. */
    public static Review of(Booking booking, int rating, String text, Instant now) {
        if (!booking.reviewable()) {
            throw new BookingsStateConflict("bookings.not-reviewable", "Only completed bookings can be rated");
        }
        String name = booking.customer() == null ? null : booking.customer().name();
        return new Review(booking.id(), booking.orgId(), booking.service().serviceId(), booking.service().name(),
                booking.customerId(), displayName(name), rating, text, now, ReviewStatus.PENDING, null, null, 0);
    }

    /** Public and counted in ratings. */
    public boolean published() {
        return status == ReviewStatus.APPROVED;
    }

    /**
     * Moderation approves the review. Approving an approved review is a no-op (safe for retried queue decisions);
     * a rejected review can be reinstated.
     */
    public Review approve(String by, String note, Instant now) {
        if (status == ReviewStatus.APPROVED) {
            return this;
        }
        return new Review(bookingId, orgId, serviceId, serviceName, customerId, reviewerName, rating, text, createdAt,
                ReviewStatus.APPROVED, new Moderation(by, now, null, note(note, false)), reply, version);
    }

    /**
     * Moderation removes the review (also after it was published). Rejecting a rejected review is a no-op.
     */
    public Review reject(String by, ReviewRejectReason reason, String note, Instant now) {
        if (reason == null) {
            throw new BookingsRuleViolation("bookings.invalid-moderation", "Choose why the review is removed",
                    Map.of("reasonCode", "Required"));
        }
        if (status == ReviewStatus.REJECTED) {
            return this;
        }
        return new Review(bookingId, orgId, serviceId, serviceName, customerId, reviewerName, rating, text, createdAt,
                ReviewStatus.REJECTED, new Moderation(by, now, reason, note(note, reason == ReviewRejectReason.OTHER)),
                reply, version);
    }

    /** The provider replies once, to a published review. */
    public Review replyBy(String by, String replyText, Instant now) {
        if (status != ReviewStatus.APPROVED) {
            throw new BookingsStateConflict("bookings.review-not-published",
                    "You can reply once the review is published");
        }
        if (reply != null) {
            throw new BookingsStateConflict("bookings.already-replied", "You have already replied to this review");
        }
        String t = replyText == null ? "" : replyText.trim();
        if (t.isEmpty() || t.length() > MAX_REPLY) {
            throw new BookingsRuleViolation("bookings.invalid-reply", "Replies are 1 to " + MAX_REPLY + " characters",
                    Map.of("text", "1 to " + MAX_REPLY + " characters"));
        }
        return new Review(bookingId, orgId, serviceId, serviceName, customerId, reviewerName, rating, text, createdAt,
                status, moderation, new ProviderReply(t, by, now), version);
    }

    /** "Adaeze Nwosu" → "Adaeze N."; a single name stays as it is; no name → "Verified customer". */
    static String displayName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "Verified customer";
        }
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        String last = parts[parts.length - 1];
        return parts[0] + " " + last.substring(0, 1).toUpperCase(Locale.ROOT) + ".";
    }

    private static String note(String note, boolean required) {
        String n = note == null || note.isBlank() ? null : note.trim();
        if (required && n == null) {
            throw new BookingsRuleViolation("bookings.invalid-moderation", "Explain why in the note",
                    Map.of("note", "Required for OTHER"));
        }
        if (n != null && n.length() > MAX_NOTE) {
            throw new BookingsRuleViolation("bookings.invalid-moderation", "Notes are at most " + MAX_NOTE
                    + " characters", Map.of("note", "At most " + MAX_NOTE + " characters"));
        }
        return n;
    }
}
