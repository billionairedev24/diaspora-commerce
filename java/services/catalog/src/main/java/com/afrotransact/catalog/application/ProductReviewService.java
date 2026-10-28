package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.catalog.domain.ProductReview;
import com.afrotransact.catalog.domain.RatingSummary;
import com.afrotransact.catalog.domain.ReviewDraft;
import com.afrotransact.catalog.domain.ReviewReasonCode;
import com.afrotransact.catalog.domain.ReviewSort;

/**
 * Verified-purchase product reviews (S324), customer side and public reads. Only a customer with a delivered order
 * line for the product (orders-service says so) can review it, once; they can edit within the edit window and delete.
 * New reviews pass automatic checks and are published or wait in trust-ops' moderation queue.
 */
public interface ProductReviewService {

    /**
     * The signed-in customer's standing with the product: whether they may review it, and their review if they wrote
     * one (in any state, with the moderation reason when it was refused).
     */
    MyReview mine(String atin);

    /**
     * Writes the review. The customer must have a delivered order line for the product.
     *
     * @param reviewerName the shopper's account name; only first name and last initial are kept and shown
     * @throws com.afrotransact.platform.web.error.ForbiddenActionException {@code catalog.review-not-verified-purchase}
     *                                                                      without a delivered purchase
     * @throws com.afrotransact.catalog.domain.ReviewStateConflict          {@code catalog.review-already-exists}
     *                                                                      the second time
     */
    ProductReview write(String atin, String reviewerName, ReviewDraft draft);

    /** Edits the customer's review within the edit window; the checks run again (see {@link ProductReview#edit}). */
    ProductReview edit(String atin, ReviewDraft draft);

    /** Deletes the customer's review (any time); the rating is recomputed. */
    void delete(String atin);

    /**
     * Reports a published review: any signed-in customer, or a member of the product owner's organisation. One report
     * per reporter and review; the first report of a text queues the review for moderation.
     */
    ReportResult report(UUID reviewId, ReviewReasonCode reasonCode, String details);

    /** Published reviews of a live product, with its rating. */
    PublicReviews publicReviews(String atin, Integer stars, ReviewSort sort, int page, int size);

    /** The rating of a live product (zeros when it has no published review). */
    RatingSummary rating(String atin);

    /** The stored rating of a product, for the public product page. */
    RatingSummary ratingOf(UUID productId);

    /**
     * What the customer can do about a product.
     *
     * @param eligible      true when they received it and have no live review yet
     * @param reason        why not, when they cannot write: {@code not-purchased} or {@code already-reviewed}
     * @param review        their review, null when they have none
     * @param editable      true while the review can still be edited
     * @param editableUntil end of the edit window (null without a review)
     */
    record MyReview(boolean eligible, String reason, ProductReview review, boolean editable, Instant editableUntil) {
    }

    /** One public page and the rating it belongs to. */
    record PublicReviews(RatingSummary rating, PageView<ProductReview> page) {
    }

    /**
     * @param created false when this reporter had already reported the review
     * @param reports reports on the review's current text
     */
    record ReportResult(boolean created, int reports) {
    }
}
