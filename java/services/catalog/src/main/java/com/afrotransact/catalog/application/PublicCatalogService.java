package com.afrotransact.catalog.application;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.Variant;

/** Anonymous reads of live products for the storefront, cart and search (no moderation details). */
public interface PublicCatalogService {

    /** A LIVE product by its product ATIN. */
    Product liveProduct(String atin);

    /** A LIVE product's variant by the variant ATIN, with its product. */
    LiveVariant liveVariant(String variantAtin);

    /** A variant and the product it belongs to. */
    record LiveVariant(Product product, Variant variant) {
    }
}
