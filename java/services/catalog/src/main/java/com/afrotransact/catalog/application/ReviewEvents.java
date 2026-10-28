package com.afrotransact.catalog.application;

import java.time.Instant;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductReview;
import com.afrotransact.catalog.domain.RatingSummary;
import com.afrotransact.catalog.domain.ReviewStatus;
import com.afrotransact.catalog.port.ProductReviewRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes catalog.review.* events and keeps the product's rating: after every change of a review's state the
 * aggregate is rewritten from the published reviews in the same transaction, and {@code catalog.product.rating_changed}
 * is published when it moved.
 */
@Component
class ReviewEvents {

    private final DomainEventPublisher events;
    private final ProductReviewRepository reviews;

    ReviewEvents(DomainEventPublisher events, ProductReviewRepository reviews) {
        this.events = events;
        this.reviews = reviews;
    }

    /** {@code type} for the review, keyed by review id so a review's events stay in order. */
    void publish(String type, ProductReview review) {
        publish(type, review, null);
    }

    /** {@code catalog.review.reported} names the reason of the report that raised it. */
    void publish(String type, ProductReview review, String reportReason) {
        events.publish(type, CatalogEvents.AGGREGATE_REVIEW, review.id().toString(),
                ReviewEventPayload.of(review, reportReason));
    }

    /**
     * Publishes the event that says where the review ended up after a write or an edit: submitted (waiting for
     * moderation), updated (it was public and stayed public) or published.
     */
    void publishOutcome(ProductReview saved, boolean wasPublished) {
        if (saved.status() == ReviewStatus.PENDING) {
            publish(CatalogEvents.REVIEW_SUBMITTED, saved);
        } else {
            publish(wasPublished ? CatalogEvents.REVIEW_UPDATED : CatalogEvents.REVIEW_PUBLISHED, saved);
        }
    }

    /**
     * Rewrites the product's rating from its published reviews and publishes {@code catalog.product.rating_changed}
     * when the average, the count or the breakdown moved.
     */
    RatingSummary refreshRating(Product product, Instant now) {
        RatingSummary before = reviews.rating(product.id()).orElse(RatingSummary.NONE);
        RatingSummary after = reviews.rewriteRating(product.id(), product.atin().value(), now);
        if (!before.equals(after)) {
            events.publish(CatalogEvents.PRODUCT_RATING_CHANGED, CatalogEvents.AGGREGATE_PRODUCT,
                    product.id().toString(), RatingChangedPayload.of(product, after));
        }
        return after;
    }
}
