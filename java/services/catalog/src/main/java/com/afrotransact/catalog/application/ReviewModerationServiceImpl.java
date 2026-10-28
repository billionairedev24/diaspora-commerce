package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductReview;
import com.afrotransact.catalog.domain.ReviewReasonCode;
import com.afrotransact.catalog.domain.ReviewStatus;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.ProductReviewRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReviewModerationServiceImpl implements ReviewModerationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ProductReviewRepository reviews;
    private final ProductRepository products;
    private final PermissionChecker permissions;
    private final ReviewEvents events;
    private final Clock clock;

    ReviewModerationServiceImpl(ProductReviewRepository reviews, ProductRepository products,
                                PermissionChecker permissions, ReviewEvents events, Clock clock) {
        this.reviews = reviews;
        this.products = products;
        this.permissions = permissions;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<ProductReview> list(ReviewStatus status, String atin, int page, int size) {
        permissions.require(CatalogPermissions.MODERATE);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new CatalogRuleViolation("page must be 0 or more and size 1 to " + MAX_PAGE_SIZE,
                    Map.of("page", "Invalid page"));
        }
        if (status == ReviewStatus.DELETED) {
            throw new CatalogRuleViolation("Deleted reviews are not listed", Map.of("status", "Not listed"));
        }
        String key = atin == null || atin.isBlank() ? null : atin.strip().toUpperCase(Locale.ROOT);
        return ProductReviewServiceImpl.view(reviews.byStatus(status, key, page, size));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductReview get(UUID reviewId) {
        permissions.require(CatalogPermissions.MODERATE);
        return find(reviewId);
    }

    @Override
    @Transactional
    public ProductReview approve(UUID reviewId, String note) {
        Actor actor = permissions.require(CatalogPermissions.MODERATE);
        return decide(reviewId, r -> r.approve(actor.subject(), note, Instant.now(clock)),
                CatalogEvents.REVIEW_PUBLISHED);
    }

    @Override
    @Transactional
    public ProductReview reject(UUID reviewId, ReviewReasonCode reasonCode, String note) {
        Actor actor = permissions.require(CatalogPermissions.MODERATE);
        return decide(reviewId, r -> r.reject(actor.subject(), reasonCode, note, Instant.now(clock)),
                CatalogEvents.REVIEW_REJECTED);
    }

    @Override
    @Transactional
    public ProductReview remove(UUID reviewId, ReviewReasonCode reasonCode, String note) {
        Actor actor = permissions.require(CatalogPermissions.MODERATE);
        return decide(reviewId, r -> r.remove(actor.subject(), reasonCode, note, Instant.now(clock)),
                CatalogEvents.REVIEW_REMOVED);
    }

    /** Applies a decision; a decision that changes nothing (a retry) publishes nothing. */
    private ProductReview decide(UUID reviewId, UnaryOperator<ProductReview> decision, String eventType) {
        ProductReview current = find(reviewId);
        ProductReview decided = decision.apply(current);
        if (decided == current) {
            return current;
        }
        ProductReview saved = reviews.update(decided);
        events.publish(eventType, saved);
        Product product = products.findById(saved.productId()).orElseThrow(() -> notFound(reviewId));
        events.refreshRating(product, Instant.now(clock));
        return saved;
    }

    private ProductReview find(UUID reviewId) {
        return reviews.findById(reviewId).filter(r -> r.status() != ReviewStatus.DELETED)
                .orElseThrow(() -> notFound(reviewId));
    }

    private static NotFoundException notFound(UUID reviewId) {
        return new NotFoundException("catalog.review-not-found", "Review " + reviewId + " not found");
    }
}
