package com.afrotransact.catalog.api;

import java.util.Map;
import java.util.Optional;

/**
 * What the orders module reads from catalog's public variant endpoint ({@code GET /v1/public/variants/{atin}}) at checkout:
 * the listing as shoppers see it (title, first picture, price, owner) plus the product facts the order rules use. No scope is
 * needed over HTTP, so none is needed here; the call still runs under an in-process caller principal.
 */
public interface ProductListingApi {

    /** @return the live variant's listing, empty when the ATIN is unknown or not live (HTTP 404) */
    Optional<LiveListing> liveListing(String variantAtin);

    /**
     * @param imageUrl        the product's first picture, null when it has none
     * @param ownerKind       SELLER or RETAIL (the page's owner kind)
     * @param productType     the product type code, null while the page has none
     * @param attributes      the product's attribute answers by key
     * @param sellerBehaviour the owner listing's behaviour facts
     */
    record LiveListing(String variantAtin, String productAtin, String title, String imageUrl, String sellerOrgId,
                       String ownerKind, String productType, Map<String, String> attributes, long priceMinor,
                       String currency, Map<String, Object> sellerBehaviour) {

        public LiveListing {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            sellerBehaviour = sellerBehaviour == null ? Map.of() : Map.copyOf(sellerBehaviour);
        }
    }
}
