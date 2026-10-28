package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductReview;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.PurchaseProof;
import com.afrotransact.catalog.domain.RatingSummary;
import com.afrotransact.catalog.domain.ReviewContentPolicy;
import com.afrotransact.catalog.domain.ReviewDraft;
import com.afrotransact.catalog.domain.ReviewPhoto;
import com.afrotransact.catalog.domain.ReviewReasonCode;
import com.afrotransact.catalog.domain.ReviewReport;
import com.afrotransact.catalog.domain.ReviewSort;
import com.afrotransact.catalog.domain.ReviewStateConflict;
import com.afrotransact.catalog.port.ListingSuppressionRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.ProductReviewRepository;
import com.afrotransact.catalog.port.PurchaseVerifier;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProductReviewServiceImpl implements ProductReviewService {

    static final int MAX_PAGE_SIZE = 50;

    private final ProductRepository products;
    private final ListingSuppressionRepository suppressions;
    private final ProductReviewRepository reviews;
    private final PurchaseVerifier purchases;
    private final ReviewContentPolicy policy;
    private final ReviewSettings settings;
    private final ReviewEvents events;
    private final ActorResolver actors;
    private final ResourceAccessChecker access;
    private final Clock clock;

    ProductReviewServiceImpl(ProductRepository products, ListingSuppressionRepository suppressions,
                             ProductReviewRepository reviews, PurchaseVerifier purchases, ReviewContentPolicy policy,
                             ReviewSettings settings, ReviewEvents events, ActorResolver actors,
                             ResourceAccessChecker access, Clock clock) {
        this.products = products;
        this.suppressions = suppressions;
        this.reviews = reviews;
        this.purchases = purchases;
        this.policy = policy;
        this.settings = settings;
        this.events = events;
        this.actors = actors;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public MyReview mine(String atin) {
        Actor customer = customer();
        Product product = findProduct(atin);
        Instant now = Instant.now(clock);
        Optional<ProductReview> existing = reviews.findLive(product.id(), customer.subject());
        if (existing.isPresent()) {
            ProductReview r = existing.get();
            return new MyReview(false, "already-reviewed", r, r.editable(settings.editWindow(), now),
                    r.editableUntil(settings.editWindow()));
        }
        boolean bought = reviewable(product) && purchases.deliveredPurchase(customer.subject(), product.atin().value())
                .isPresent();
        return new MyReview(bought, bought ? null : "not-purchased", null, false, null);
    }

    @Override
    @Transactional
    public ProductReview write(String atin, String reviewerName, ReviewDraft draft) {
        Actor customer = customer();
        Product product = findProduct(atin);
        if (!reviewable(product)) {
            throw productNotFound(atin);
        }
        if (reviews.findLive(product.id(), customer.subject()).isPresent()) {
            throw new ReviewStateConflict("catalog.review-already-exists", "You have already reviewed this item");
        }
        PurchaseProof proof = purchases.deliveredPurchase(customer.subject(), product.atin().value())
                .orElseThrow(() -> new ForbiddenActionException("catalog.review-not-verified-purchase",
                        "Only customers who received this item can review it"));
        checkPhotos(draft);
        Instant now = Instant.now(clock);
        ProductReview saved = reviews.insert(ProductReview.write(UUID.randomUUID(), product, customer.subject(),
                reviewerName, proof, draft, policy, now));
        events.publishOutcome(saved, false);
        events.refreshRating(product, now);
        return saved;
    }

    @Override
    @Transactional
    public ProductReview edit(String atin, ReviewDraft draft) {
        Actor customer = customer();
        Product product = findProduct(atin);
        ProductReview current = reviews.findLive(product.id(), customer.subject())
                .orElseThrow(() -> reviewNotFound(atin));
        checkPhotos(draft);
        Instant now = Instant.now(clock);
        ProductReview saved = reviews.update(current.edit(draft, policy, settings.editWindow(), now));
        if (current.reportCount() > 0) {
            reviews.clearReports(saved.id());
        }
        events.publishOutcome(saved, current.published());
        events.refreshRating(product, now);
        return saved;
    }

    @Override
    @Transactional
    public void delete(String atin) {
        Actor customer = customer();
        Product product = findProduct(atin);
        ProductReview current = reviews.findLive(product.id(), customer.subject())
                .orElseThrow(() -> reviewNotFound(atin));
        Instant now = Instant.now(clock);
        ProductReview saved = reviews.update(current.delete(now));
        events.publish(CatalogEvents.REVIEW_DELETED, saved);
        events.refreshRating(product, now);
    }

    @Override
    @Transactional
    public ReportResult report(UUID reviewId, ReviewReasonCode reasonCode, String details) {
        Actor actor = actors.current();
        ProductReview review = reviews.findById(reviewId).filter(ProductReview::published)
                .orElseThrow(() -> new NotFoundException("catalog.review-not-found", "Review " + reviewId
                        + " not found"));
        ReviewReport.Reporter kind = reporterKind(actor, review);
        Instant now = Instant.now(clock);
        boolean created = reviews.addReport(new ReviewReport(UUID.randomUUID(), review.id(), actor.subject(), kind,
                reasonCode, details, now));
        int reports = reviews.countReports(review.id());
        if (created) {
            ProductReview saved = reviews.update(review.withReportCount(reports));
            events.publish(CatalogEvents.REVIEW_REPORTED, saved, reasonCode.name());
        }
        return new ReportResult(created, reports);
    }

    @Override
    @Transactional(readOnly = true)
    public PublicReviews publicReviews(String atin, Integer stars, ReviewSort sort, int page, int size) {
        if (stars != null && (stars < 1 || stars > 5)) {
            throw new CatalogRuleViolation("stars must be 1 to 5", Map.of("stars", "Between 1 and 5"));
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new CatalogRuleViolation("page must be 0 or more and size 1 to " + MAX_PAGE_SIZE,
                    Map.of("page", "Invalid page"));
        }
        Product product = publicProduct(atin);
        return new PublicReviews(ratingOf(product.id()), view(reviews.published(product.id(), stars,
                sort == null ? ReviewSort.NEWEST : sort, page, size)));
    }

    @Override
    @Transactional(readOnly = true)
    public RatingSummary rating(String atin) {
        return ratingOf(publicProduct(atin).id());
    }

    @Override
    @Transactional(readOnly = true)
    public RatingSummary ratingOf(UUID productId) {
        return reviews.rating(productId).orElse(RatingSummary.NONE);
    }

    /** A customer, or a member of the product owner's organisation (a seller reports; sellers never decide). */
    private ReviewReport.Reporter reporterKind(Actor actor, ProductReview review) {
        if (actor.type() == PrincipalType.CUSTOMER) {
            if (actor.subject().equals(review.customerId())) {
                throw new ForbiddenActionException("catalog.review-own-report", "You cannot report your own review");
            }
            return ReviewReport.Reporter.CUSTOMER;
        }
        if (actor.type() == PrincipalType.SELLER_MEMBER && actor.orgId().map(review.sellerOrgId()::equals)
                .orElse(false)) {
            access.requireForOrg(CatalogPermissions.WRITE, CatalogRelations.ORG_VIEW_PRODUCTS, review.sellerOrgId());
            return ReviewReport.Reporter.SELLER;
        }
        throw new ForbiddenActionException("catalog.review-report-forbidden",
                "Only shoppers and the product's seller can report a review");
    }

    private Actor customer() {
        Actor actor = actors.current();
        if (actor.type() != PrincipalType.CUSTOMER) {
            throw new ForbiddenActionException("catalog.customer-only", "Only signed-in shoppers write reviews");
        }
        return actor;
    }

    private void checkPhotos(ReviewDraft draft) {
        for (ReviewPhoto p : draft.photos()) {
            if (p.valid() && !settings.photoHostAllowed(p.host())) {
                throw new CatalogRuleViolation("Check your review", Map.of("photos",
                        "Photos must be uploaded through AfroTransact"));
            }
        }
    }

    private Product findProduct(String raw) {
        Atin atin = parse(raw);
        return products.findByAtin(atin).orElseThrow(() -> productNotFound(raw));
    }

    /** LIVE or PAUSED: a customer can still review what they bought while it is paused. */
    private static boolean reviewable(Product p) {
        return p.status() == ProductStatus.LIVE || p.status() == ProductStatus.PAUSED;
    }

    private Product publicProduct(String raw) {
        return products.findByAtin(parse(raw))
                .filter(p -> p.status() == ProductStatus.LIVE && !suppressions.isHidden(p.owner().orgId()))
                .orElseThrow(() -> productNotFound(raw));
    }

    private static Atin parse(String raw) {
        try {
            return new Atin(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw productNotFound(raw);
        }
    }

    private static NotFoundException productNotFound(String atin) {
        return new NotFoundException("catalog.product-not-found", "Product " + atin + " not found");
    }

    private static NotFoundException reviewNotFound(String atin) {
        return new NotFoundException("catalog.review-not-found", "You have no review of " + atin);
    }

    static PageView<ProductReview> view(ProductReviewRepository.Page<ProductReview> page) {
        return new PageView<>(page.items(), page.total(), page.page(), page.size());
    }
}
