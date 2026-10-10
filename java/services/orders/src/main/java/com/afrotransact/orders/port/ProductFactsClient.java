package com.afrotransact.orders.port;

import java.util.Collection;
import java.util.Map;

/** catalog-service {@code GET /v1/public/variants/{atin}}: product type (perishable?) and commission category. */
public interface ProductFactsClient {

    /** Facts per variant ATIN; ATINs the catalog does not answer for are missing from the map. */
    Map<String, Facts> facts(Collection<String> variantAtins);

    /**
     * What each variant sells for (S272 late pre-order adds are priced from the catalog, there is no cart). Variants
     * that are not live are missing from the map.
     */
    Map<String, Listing> listings(Collection<String> variantAtins);

    /** What orders needs to know about a product. */
    record Facts(String variantAtin, String productType, boolean perishable, String category) {
    }

    /**
     * A live variant and its seller.
     *
     * @param ownerKind PLATFORM (AfroTransact Retail) or SELLER
     */
    record Listing(String variantAtin, String productAtin, String title, String imageUrl, String sellerOrgId,
                   String ownerKind, long priceMinor, String currency) {
    }
}
