package com.afrotransact.catalog.application;

/** Event types published by the catalog service (documented in contracts/asyncapi/events.yaml). */
public final class CatalogEvents {

    public static final String AGGREGATE_PRODUCT = "product";
    public static final String AGGREGATE_CATEGORY_REQUEST = "category-request";
    public static final String AGGREGATE_TAXONOMY = "taxonomy";

    /** Aggregate of change requests and no-barcode exemptions (topic at.change-request.events, key = request id). */
    public static final String AGGREGATE_CHANGE_REQUEST = "change-request";
    /** A seller filed a suggest-an-edit, wrong-match or duplicate-page request (the page is not changed). */
    public static final String CHANGE_REQUEST_CREATED = "catalog.change_request.created";
    /** Staff decided a change request: who, what and why. */
    public static final String CHANGE_REQUEST_DECIDED = "catalog.change_request.decided";
    /** A no-barcode item waits for staff approval (the page is PENDING_EXEMPTION). */
    public static final String GTIN_EXEMPTION_REQUESTED = "catalog.gtin_exemption.requested";
    /** Staff decided a no-barcode exemption. */
    public static final String GTIN_EXEMPTION_DECIDED = "catalog.gtin_exemption.decided";
    /** Two duplicate pages were merged (aggregate product, key = the surviving product id). */
    public static final String PRODUCT_MERGED = "catalog.product.merged";

    public static final String PRODUCT_CREATED = "catalog.product.created";
    public static final String PRODUCT_APPROVED = "catalog.product.approved";
    public static final String PRODUCT_REJECTED = "catalog.product.rejected";
    public static final String PRODUCT_PAUSED = "catalog.product.paused";
    public static final String PRODUCT_RESUMED = "catalog.product.resumed";
    /** Listing settings changed without a status change (S272: pre-order flags). */
    public static final String PRODUCT_UPDATED = "catalog.product.updated";
    /**
     * The seller changed price, quantity, compare-at price, condition, fulfilment, handling time or ship-from (one-step
     * form or bulk update), or added variants: offers-service updates the seller's offers (S307).
     */
    public static final String PRODUCT_TERMS_CHANGED = "catalog.product.terms_changed";
    /**
     * Tax facts or the category of a product changed (a variant's product facts, the seller's behaviour facts, an
     * evidence file or the category). Published together with {@code catalog.product.updated}; tax-returns re-resolves
     * the stored classification of every variant and queues it for review.
     */
    public static final String PRODUCT_TAX_FACTS_CHANGED = "catalog.product.tax-facts-changed";
    /** A rejected listing was edited and is back in review (trust-ops queues it like a new listing). */
    public static final String PRODUCT_RESUBMITTED = "catalog.product.resubmitted";
    public static final String AGGREGATE_IMPORT = "product-import";
    /** A bulk upload finished importing (S308): counts and whether the seller is notified. */
    public static final String IMPORT_COMPLETED = "catalog.import.completed";
    /** The owner's listings were hidden by seller-service compliance enforcement (payload sellerHidden=true). */
    public static final String PRODUCT_HIDDEN = "catalog.product.hidden";
    /** The owner's listings were restored (payload sellerHidden=false, status unchanged). */
    public static final String PRODUCT_RESTORED = "catalog.product.restored";
    /**
     * A listing's main image needs the seller's attention, or no longer does (S392): payload productId, atin, ownerOrg,
     * state (OK, IMPROVE or FAIL), reasonCodes, failingSince and hideAfter (the end of the grace period; null unless
     * FAIL). Seller Center shows the "Improve your image" task from it; nothing is hidden by this event.
     */
    public static final String LISTING_IMAGE_TASK = "catalog.listing.image_task";
    /** A live listing was reported by a shopper, flagged by staff or flagged automatically (trust-ops queues it). */
    public static final String PRODUCT_FLAGGED = "catalog.product.flagged";
    /** Trust and Safety took a live listing down (status TAKEN_DOWN, reasonCode set). */
    public static final String PRODUCT_TAKEN_DOWN = "catalog.product.taken_down";
    /** A takedown was reversed; status is the listing's status before the takedown (LIVE or PAUSED). */
    public static final String PRODUCT_REINSTATED = "catalog.product.reinstated";
    /**
     * The product's average rating or review count changed (S324): a review was published, edited, removed or
     * deleted. Aggregate {@code product} (topic at.product.events, key = product id), so it stays in order with the
     * product's lifecycle; the search indexer updates rating and reviewCount from it.
     */
    public static final String PRODUCT_RATING_CHANGED = "catalog.product.rating_changed";

    /** Aggregate of product review events (topic at.product-review.events, key = review id). */
    public static final String AGGREGATE_REVIEW = "product-review";
    /** A review is waiting for a human: it failed the automatic checks, or was edited after a rejection (PENDING). */
    public static final String REVIEW_SUBMITTED = "catalog.review.submitted";
    /** A review is public: it passed the checks, or moderation approved it. */
    public static final String REVIEW_PUBLISHED = "catalog.review.published";
    /** The customer edited a published review and it stayed public. */
    public static final String REVIEW_UPDATED = "catalog.review.updated";
    /** Moderation refused a pending review. */
    public static final String REVIEW_REJECTED = "catalog.review.rejected";
    /** Moderation took a review down. */
    public static final String REVIEW_REMOVED = "catalog.review.removed";
    /** The customer deleted their review. */
    public static final String REVIEW_DELETED = "catalog.review.deleted";
    /** A customer or the product owner reported a published review (trust-ops queues it). */
    public static final String REVIEW_REPORTED = "catalog.review.reported";
    /** The product owner replied to a review. */
    public static final String REVIEW_REPLIED = "catalog.review.replied";
    /**
     * Staff changed the taxonomy (a node created, renamed, moved, reordered, retired or restored, or a product type's
     * schema edited). Consumers that cache the category tree (indexer, delivery-runs) drop their copy.
     */
    public static final String TAXONOMY_CHANGED = "catalog.taxonomy.changed";
    public static final String CATEGORY_REQUESTED = "catalog.category-request.created";
    public static final String CATEGORY_APPROVED = "catalog.category-request.approved";
    public static final String CATEGORY_REJECTED = "catalog.category-request.rejected";

    private CatalogEvents() {
    }
}
