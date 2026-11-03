package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.ProductReview;
import com.afrotransact.catalog.port.ProductReviewRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SellerReviewServiceImpl implements SellerReviewService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ProductReviewRepository reviews;
    private final PermissionChecker permissions;
    private final ResourceAccessChecker access;
    private final ReviewEvents events;
    private final Clock clock;

    SellerReviewServiceImpl(ProductReviewRepository reviews, PermissionChecker permissions,
                            ResourceAccessChecker access, ReviewEvents events, Clock clock) {
        this.reviews = reviews;
        this.permissions = permissions;
        this.access = access;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<ProductReview> list(String orgId, boolean unansweredOnly, int page, int size) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        boolean staff = actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE;
        String org = staff ? orgId : actor.orgId().orElse(null);
        if (org == null || org.isBlank()) {
            throw new CatalogRuleViolation("Name the seller organisation", Map.of("orgId", "Required"));
        }
        access.requireForOrg(CatalogPermissions.WRITE, CatalogRelations.ORG_VIEW_PRODUCTS, org);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new CatalogRuleViolation("page must be 0 or more and size 1 to " + MAX_PAGE_SIZE,
                    Map.of("page", "Invalid page"));
        }
        return ProductReviewServiceImpl.view(reviews.publishedForOwner(org, unansweredOnly, page, size));
    }

    @Override
    @Transactional
    public ProductReview reply(UUID reviewId, String text) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        ProductReview current = reviews.findById(reviewId)
                .filter(r -> actor.orgId().map(r.sellerOrgId()::equals).orElse(false)
                        && actor.type() == PrincipalType.SELLER_MEMBER)
                .orElseThrow(() -> new NotFoundException("catalog.review-not-found", "Review " + reviewId
                        + " not found"));
        access.requireForOrg(CatalogPermissions.WRITE, CatalogRelations.ORG_MANAGE_PRODUCTS, current.sellerOrgId());
        ProductReview saved = reviews.update(current.replyBy(actor.subject(), text, Instant.now(clock)));
        events.publish(CatalogEvents.REVIEW_REPLIED, saved);
        return saved;
    }
}
