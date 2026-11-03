package com.afrotransact.trustops.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer review waiting for moderation. Two kinds share the record: a review of a completed booking, announced
 * by bookings-service's {@code booking.review.submitted} (one review per booking, so the booking id is the review id),
 * and a verified-purchase review of a product, announced by catalog's {@code catalog.review.submitted} (failed the
 * automatic checks) or {@code catalog.review.reported} (a published review someone reported); there {@code bookingId}
 * is the review id, {@code serviceId} the product id and {@code serviceName} the product title.
 *
 * @param kind         what was reviewed
 * @param bookingId    the review id (the booking id for booking reviews)
 * @param orgId        the provider organisation or product owner that was reviewed
 * @param customerId   the author (token subject); never public, shown to moderators for conflict-of-interest checks
 * @param reviewerName the public display name, e.g. "Adaeze N."
 * @param rating       1 to 5 stars
 * @param text         the review text (optional)
 * @param atin         the product ATIN (product reviews)
 * @param title        the review title (product reviews, optional)
 * @param photoUrls    the review photos (product reviews)
 * @param flags        what the automatic checks found (product reviews): PROFANITY, LINK, EMAIL, PHONE
 * @param reports      reports on the review while it is public (product reviews; 0 for a pending submission)
 * @param reportReason reason code of the first report (product reviews)
 */
public record ReviewSubmission(Kind kind, UUID bookingId, String orgId, UUID serviceId, String serviceName,
                               String customerId, String reviewerName, int rating, String text, Instant submittedAt,
                               String atin, String title, List<String> photoUrls, List<String> flags, int reports,
                               String reportReason) {

    /** What was reviewed. */
    public enum Kind {
        BOOKING,
        PRODUCT
    }

    public ReviewSubmission {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(bookingId, "bookingId");
        if (orgId == null || orgId.isBlank()) {
            throw new IllegalArgumentException("orgId is required");
        }
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("rating must be 1 to 5, was " + rating);
        }
        serviceName = blankToNull(serviceName, 200);
        reviewerName = blankToNull(reviewerName, 120);
        text = blankToNull(text, kind == Kind.PRODUCT ? 4000 : 2000);
        atin = blankToNull(atin, 10);
        title = blankToNull(title, 200);
        photoUrls = photoUrls == null ? List.of() : List.copyOf(photoUrls);
        flags = flags == null ? List.of() : List.copyOf(flags);
        reports = Math.max(reports, 0);
        reportReason = blankToNull(reportReason, 40);
    }

    /** A review of a completed booking. */
    public ReviewSubmission(UUID bookingId, String orgId, UUID serviceId, String serviceName, String customerId,
                            String reviewerName, int rating, String text, Instant submittedAt) {
        this(Kind.BOOKING, bookingId, orgId, serviceId, serviceName, customerId, reviewerName, rating, text,
                submittedAt, null, null, List.of(), List.of(), 0, null);
    }

    /**
     * A verified-purchase review of a product.
     *
     * @param reviewId     the review id
     * @param sellerOrgId  the product's owner
     * @param productId    the reviewed product
     * @param reports      0 for a pending submission, at least 1 for a reported published review
     */
    public static ReviewSubmission productReview(UUID reviewId, String sellerOrgId, UUID productId, String atin,
                                                 String productTitle, String customerId, String reviewerName,
                                                 int rating, String title, String text, List<String> photoUrls,
                                                 List<String> flags, int reports, String reportReason,
                                                 Instant submittedAt) {
        return new ReviewSubmission(Kind.PRODUCT, reviewId, sellerOrgId, productId, productTitle, customerId,
                reviewerName, rating, text, submittedAt, atin, title, photoUrls, flags, reports, reportReason);
    }

    public boolean productReview() {
        return kind == Kind.PRODUCT;
    }

    /** A published review that someone reported (as opposed to a submission waiting for its first decision). */
    public boolean reported() {
        return reports > 0;
    }

    /** Another report arrived for the same open item. */
    public ReviewSubmission reportedAgain() {
        return new ReviewSubmission(kind, bookingId, orgId, serviceId, serviceName, customerId, reviewerName, rating,
                text, submittedAt, atin, title, photoUrls, flags, reports + 1, reportReason);
    }

    /** One line for the queue and the audit log, e.g. "★★★★☆ review of Box braids". */
    public String summary() {
        String stars = "★".repeat(rating) + "☆".repeat(5 - rating);
        String what = serviceName == null ? (productReview() ? "a product" : "a service") : serviceName;
        return stars + (reported() ? " reported review of " : " review of ") + what;
    }

    private static String blankToNull(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
