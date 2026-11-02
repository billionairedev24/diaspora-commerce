package com.afrotransact.catalog.application;

import java.util.Optional;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.sizing.SizeChart;

/**
 * Anonymous reads of live products for the storefront, cart and search (no moderation details). Products of an
 * organisation whose listings seller-service has hidden are not found.
 */
public interface PublicCatalogService {

    /** A LIVE product by its product ATIN. */
    Product liveProduct(String atin);

    /** A LIVE product's variant by the variant ATIN, with its product. */
    LiveVariant liveVariant(String variantAtin);

    /** The size chart a product links (retired or not: the product keeps showing it), if any. */
    Optional<SizeChart> sizeChartOf(Product product);

    /** A variant and the product it belongs to. */
    record LiveVariant(Product product, Variant variant) {
    }
}
