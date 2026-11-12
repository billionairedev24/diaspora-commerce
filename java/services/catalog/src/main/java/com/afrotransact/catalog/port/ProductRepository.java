package com.afrotransact.catalog.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.tax.VariantTax;

/** Persistence port for {@link Product} aggregates (optimistic locking on {@code version}). */
public interface ProductRepository {

    Product insert(Product product);

    /** Updates the product if {@code product.version()} still matches; returns the stored copy with version + 1. */
    Product update(Product product);

    /**
     * Like {@link #update} and also writes the variants: existing ids are updated, new ones inserted (new ATINs) and
     * variants no longer present deleted.
     */
    Product updateWithVariants(Product product);

    Optional<Product> findById(UUID id);

    /**
     * LIVE products that have a variant without a stored, resolved tax decision (or no variant), ordered by id. This must
     * always be empty: it is the audit behind {@code catalog_live_without_tax_decision}.
     */
    List<Product> findLiveWithoutTaxDecision(int offset, int limit);

    /** How many products {@link #findLiveWithoutTaxDecision} would list. */
    long countLiveWithoutTaxDecision();

    /**
     * Stores a variant's tax state (facts, status, missing facts, decision summary) without touching the product or its
     * version: tax re-checks and tax-returns events must not make a form that is open fail with a stale version.
     */
    void saveVariantTax(UUID variantId, VariantTax tax);

    /**
     * Products of this category (tax profile code) with their variants' tax state, keyset by id, for re-checks after a
     * status change.
     *
     * @param afterId exclusive lower bound (null for the first page)
     */
    List<Product> findByTaxProfile(String taxProfileId, UUID afterId, int limit);

    /** The organisation's product that has a variant with this seller SKU. */
    Optional<Product> findBySellerSku(String orgId, String sellerSku);

    /** The organisation's products that use the SKUs: SKU → product id and product type. */
    Map<String, SkuOwner> productsBySku(String orgId, Collection<String> sellerSkus);

    /** Which of the SKUs the organisation already uses. */
    Set<String> takenSellerSkus(String orgId, Collection<String> sellerSkus);

    /** Which of the GTINs already have a product page (any owner). */
    Set<String> existingGtins(Collection<String> gtins);

    Optional<Product> findByAtin(Atin atin);

    /** Finds the product that owns the variant with this ATIN. */
    Optional<Product> findByVariantAtin(Atin variantAtin);

    /** Finds the product whose variant carries {@code gtin} (one product page per manufacturer barcode). */
    Optional<Product> findByVariantGtin(String gtin);

    List<Product> findByCategoryRequest(UUID categoryRequestId);

    /** Every product of an organisation, any status (with variants). */
    List<Product> findByOwnerOrg(String orgId);

    /** Products whose listing links this size chart, any status (with variants), at most {@code limit}. */
    List<Product> findBySizeChart(UUID sizeChartId, int limit);

    /** How many products link each of these size charts (charts nobody links are absent). */
    Map<UUID, Long> countBySizeCharts(Collection<UUID> sizeChartIds);

    /**
     * One keyset page of an organisation's products, any status (with variants), ordered by id.
     *
     * @param afterId exclusive lower bound (null for the first page)
     */
    List<Product> findByOwnerOrgAfter(String orgId, UUID afterId, int limit);

    /**
     * One keyset page of products for the search export, any owner, ordered by id (with variants).
     *
     * @param statuses only products in one of these statuses
     * @param afterId  exclusive lower bound (null for the first page)
     * @param since    only products whose own data, rating or owner's listing visibility changed at or after this
     *                 instant (null = every product)
     */
    List<Product> findForExport(Set<ProductStatus> statuses, UUID afterId, Instant since, int limit);

    ProductPage search(ProductQuery query);

    /**
     * Sets the quantity of the owner's variant with this ATIN to what the offers service holds (it changes with
     * every sale, cancellation and stock count) and bumps the product's version, so a form opened before the change
     * is refused instead of saving a stale number. Returns false when nothing changed. Publishes no event.
     */
    boolean mirrorVariantQuantity(String ownerOrg, String variantAtin, int quantity);

    boolean sellerSkuTaken(String orgId, String sellerSku);

    /** Products per category node (nodes without products are absent), leaving out the given statuses. */
    Map<UUID, Long> countByCategory(Set<ProductStatus> excluding);

    /** Products per product-type code (types without products are absent). */
    Map<String, Long> countByProductType();

    /** Products waiting on each of the category requests (requests nobody built against are absent). */
    Map<UUID, Long> countByCategoryRequest(Collection<UUID> requestIds);

    /** The product a seller SKU belongs to. */
    record SkuOwner(UUID productId, String productTypeCode) {
    }

    /** Filter for listing products. Null fields are ignored. */
    record ProductQuery(String ownerOrgId, ProductStatus status, String text, int page, int size) {
    }

    /** One page of results. */
    record ProductPage(List<Product> items, long total, int page, int size) {
    }
}
