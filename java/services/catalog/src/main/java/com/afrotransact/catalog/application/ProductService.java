package com.afrotransact.catalog.application;

import java.util.UUID;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;

/** Product use cases: create (submit for review), read, moderate. */
public interface ProductService {

    Product create(CreateProductCommand command);

    Product get(UUID id);

    Product getByAtin(String atin);

    /**
     * A variant in any status with its product, by variant ATIN (S226): service clients and staff with
     * {@code catalog:read} (AfroTransact Retail links draft first-party products before they are live), and members
     * of the owning organisation with {@code catalog:write}.
     */
    VariantLookup variant(String variantAtin);

    /** A variant and its product, whatever the product's status. */
    record VariantLookup(Product product, Variant variant) {
    }

    /** Products of the caller's organisation (staff see all and may filter by org). */
    PageView<Product> list(String ownerOrgId, ProductStatus status, String text, int page, int size);

    Product approve(UUID id);

    Product reject(UUID id, String note);

    Product pause(UUID id);

    Product resume(UUID id);

    /**
     * Sets the pre-order flags (S272): owner organisation members with {@code catalog:write} or staff. Publishes
     * {@code catalog.product.updated}.
     */
    Product setPreorderFlags(UUID id, boolean preorderEligible, boolean shipsNow);
}
