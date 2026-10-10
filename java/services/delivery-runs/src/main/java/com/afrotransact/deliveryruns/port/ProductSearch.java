package com.afrotransact.deliveryruns.port;

import java.util.List;
import java.util.Set;

/**
 * search-service (local port 8201, public {@code GET /v1/search}): live products of the given catalog categories in a
 * market, for the late-add product picker. Throws {@link UpstreamUnavailableException} when search cannot answer.
 */
public interface ProductSearch {

    /** Products in any of {@code categories} (case-insensitive match on the category path), by name. */
    List<Product> inCategories(Set<String> categories, String market, int limit);

    /**
     * A product offer.
     *
     * @param atin       variant ATIN
     * @param name       title
     * @param priceMinor buy-box price in minor units
     * @param currency   ISO 4217
     * @param sellerName seller shown on the offer
     */
    record Product(String atin, String name, long priceMinor, String currency, String sellerName) {
    }
}
