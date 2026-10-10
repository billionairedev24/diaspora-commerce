package com.afrotransact.catalog.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Catalog product aggregate. One product page per item; other sellers attach offers to it via GTIN match.
 *
 * @param categoryId        resolved category leaf, null while waiting on a category request
 * @param categoryRequestId the pending request the product was built against, if any
 * @param preorderEligible  may be sold in a community pre-order window (S272); defaults to true for food product
 *                          types ({@link #FOOD_PRODUCT_TYPES}), false otherwise
 * @param shipsNow          always ships by carrier, never as a pre-order, even inside a window's categories (shea,
 *                          fabric …)
 */
public record Product(UUID id, Atin atin, ListingOwner owner, UUID categoryId, UUID categoryRequestId,
                      String productTypeCode, String title, Map<String, String> attributes,
                      List<ProductImage> images, String variationTheme, List<Variant> variants,
                      ProductStatus status, String moderationNote, Instant createdAt, Instant updatedAt,
                      long version, boolean preorderEligible, boolean shipsNow) {

    /** Product types sold as food: pre-order eligible by default. */
    public static final Set<String> FOOD_PRODUCT_TYPES = Set.of("grocery", "perishable");

    /** A product with the default pre-order flags of its product type. */
    public Product(UUID id, Atin atin, ListingOwner owner, UUID categoryId, UUID categoryRequestId,
                   String productTypeCode, String title, Map<String, String> attributes, List<ProductImage> images,
                   String variationTheme, List<Variant> variants, ProductStatus status, String moderationNote,
                   Instant createdAt, Instant updatedAt, long version) {
        this(id, atin, owner, categoryId, categoryRequestId, productTypeCode, title, attributes, images,
                variationTheme, variants, status, moderationNote, createdAt, updatedAt, version,
                preorderDefault(productTypeCode), false);
    }

    /** Default of {@code preorderEligible}: true for food product types. */
    public static boolean preorderDefault(String productTypeCode) {
        return productTypeCode != null && FOOD_PRODUCT_TYPES.contains(productTypeCode);
    }

    public Product {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(atin, "atin");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(title, "title");
        attributes = Map.copyOf(attributes);
        images = List.copyOf(images);
        variants = List.copyOf(variants);
        Objects.requireNonNull(status, "status");
    }

    public Product approve(Instant now) {
        if (status != ProductStatus.IN_REVIEW) {
            throw new IllegalStateException("Only products in review can be approved (was " + status + ")");
        }
        return withStatus(ProductStatus.LIVE, null, now);
    }

    public Product reject(String note, Instant now) {
        if (status != ProductStatus.IN_REVIEW && status != ProductStatus.PENDING_CATEGORY_APPROVAL) {
            throw new IllegalStateException("Product is not awaiting a decision (was " + status + ")");
        }
        return withStatus(ProductStatus.REJECTED, note, now);
    }

    /** Called when the requested category is approved: the product moves into normal listing review. */
    public Product categoryApproved(UUID resolvedCategoryId, String resolvedProductType, Instant now) {
        if (status != ProductStatus.PENDING_CATEGORY_APPROVAL) {
            return this;
        }
        return new Product(id, atin, owner, resolvedCategoryId, categoryRequestId, resolvedProductType, title,
                attributes, images, variationTheme, variants, ProductStatus.IN_REVIEW, null, createdAt, now, version,
                preorderEligible || preorderDefault(resolvedProductType), shipsNow);
    }

    public Product pause(Instant now) {
        if (status != ProductStatus.LIVE) {
            throw new IllegalStateException("Only live products can be paused");
        }
        return withStatus(ProductStatus.PAUSED, null, now);
    }

    public Product resume(Instant now) {
        if (status != ProductStatus.PAUSED) {
            throw new IllegalStateException("Only paused products can be resumed");
        }
        return withStatus(ProductStatus.LIVE, null, now);
    }

    /** Trust and Safety removes a live or paused listing; the note carries the reason. */
    public Product takeDown(String note, Instant now) {
        if (status != ProductStatus.LIVE && status != ProductStatus.PAUSED) {
            throw new IllegalStateException("Only live or paused listings can be taken down (was " + status + ")");
        }
        return withStatus(ProductStatus.TAKEN_DOWN, note, now);
    }

    /** Reverses a takedown: the listing returns to the status it had before (LIVE or PAUSED). */
    public Product restoreFromTakedown(ProductStatus previous, Instant now) {
        if (status != ProductStatus.TAKEN_DOWN) {
            throw new IllegalStateException("Only taken-down listings can be restored (was " + status + ")");
        }
        if (previous != ProductStatus.LIVE && previous != ProductStatus.PAUSED) {
            throw new IllegalArgumentException("A takedown returns to LIVE or PAUSED, not " + previous);
        }
        return withStatus(previous, null, now);
    }

    /** Sets the pre-order flags (S272). A product that always ships now is never offered as a pre-order. */
    public Product withPreorderFlags(boolean eligible, boolean ships, Instant now) {
        return new Product(id, atin, owner, categoryId, categoryRequestId, productTypeCode, title, attributes,
                images, variationTheme, variants, status, moderationNote, createdAt, now, version, eligible, ships);
    }

    /** True when a pre-order window may sell this product (eligible and not flagged ships-now). */
    public boolean preorderable() {
        return preorderEligible && !shipsNow;
    }

    /** The same product with other variants (persistence assembles them separately). */
    public Product withVariants(List<Variant> newVariants) {
        return new Product(id, atin, owner, categoryId, categoryRequestId, productTypeCode, title, attributes,
                images, variationTheme, newVariants, status, moderationNote, createdAt, updatedAt, version,
                preorderEligible, shipsNow);
    }

    private Product withStatus(ProductStatus next, String note, Instant now) {
        return new Product(id, atin, owner, categoryId, categoryRequestId, productTypeCode, title, attributes,
                images, variationTheme, variants, next, note, createdAt, now, version, preorderEligible, shipsNow);
    }
}
