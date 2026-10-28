package com.afrotransact.catalog.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.ProductReview;
import com.afrotransact.catalog.domain.RatingSummary;
import com.afrotransact.catalog.domain.ReviewReport;
import com.afrotransact.catalog.domain.ReviewSort;
import com.afrotransact.catalog.domain.ReviewStatus;

/** Persistence port for product reviews, their reports and the per-product rating (S324). */
public interface ProductReviewRepository {

    /**
     * Stores a new review.
     *
     * @throws com.afrotransact.catalog.domain.ReviewStateConflict {@code catalog.review-already-exists} when the
     *                                                             customer already has a live review of the product
     */
    ProductReview insert(ProductReview review);

    /**
     * Updates the review if {@code review.version()} still matches; returns the stored copy with version + 1.
     *
     * @throws com.afrotransact.catalog.domain.ReviewStateConflict {@code catalog.review-concurrent-update}
     */
    ProductReview update(ProductReview review);

    Optional<ProductReview> findById(UUID id);

    /** The customer's review of the product, unless they deleted it. */
    Optional<ProductReview> findLive(UUID productId, String customerId);

    /** Published reviews of a product; {@code stars} null = every rating. */
    Page<ProductReview> published(UUID productId, Integer stars, ReviewSort sort, int page, int size);

    /** Published reviews of the organisation's products, newest first; {@code unanswered} = no reply yet. */
    Page<ProductReview> publishedForOwner(String orgId, boolean unansweredOnly, int page, int size);

    /** Reviews in a state (null = any state but deleted), optionally of one product, newest first. */
    Page<ProductReview> byStatus(ReviewStatus status, String atin, int page, int size);

    /**
     * Records a report. Returns false (and changes nothing) when this reporter already reported this review.
     */
    boolean addReport(ReviewReport report);

    int countReports(UUID reviewId);

    /** Forgets the reports of a review (its text changed, so they were about something else). */
    void clearReports(UUID reviewId);

    /** Recomputes the product's rating from its published reviews, stores it and returns it. */
    RatingSummary rewriteRating(UUID productId, String atin, Instant now);

    /** The stored rating; empty when the product never had a published review. */
    Optional<RatingSummary> rating(UUID productId);

    /** Stored ratings of these products (products without one are absent). */
    Map<UUID, RatingSummary> ratings(Collection<UUID> productIds);

    /** One page of results. */
    record Page<T>(List<T> items, long total, int page, int size) {
    }
}
