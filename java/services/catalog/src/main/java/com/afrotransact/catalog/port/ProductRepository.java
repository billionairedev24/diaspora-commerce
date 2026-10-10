package com.afrotransact.catalog.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;

/** Persistence port for {@link Product} aggregates (optimistic locking on {@code version}). */
public interface ProductRepository {

    Product insert(Product product);

    /** Updates the product if {@code product.version()} still matches; returns the stored copy with version + 1. */
    Product update(Product product);

    Optional<Product> findById(UUID id);

    Optional<Product> findByAtin(Atin atin);

    /** Finds the product that owns the variant with this ATIN. */
    Optional<Product> findByVariantAtin(Atin variantAtin);

    /** Finds the product whose variant carries {@code gtin} (one product page per manufacturer barcode). */
    Optional<Product> findByVariantGtin(String gtin);

    List<Product> findByCategoryRequest(UUID categoryRequestId);

    /** Every product of an organisation, any status (with variants). */
    List<Product> findByOwnerOrg(String orgId);

    ProductPage search(ProductQuery query);

    boolean sellerSkuTaken(String orgId, String sellerSku);

    /** Filter for listing products. Null fields are ignored. */
    record ProductQuery(String ownerOrgId, ProductStatus status, String text, int page, int size) {
    }

    /** One page of results. */
    record ProductPage(List<Product> items, long total, int page, int size) {
    }
}
