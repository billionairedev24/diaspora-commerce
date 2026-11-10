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

    /**
     * What orders needs to know about a product.
     *
     * @param ownerOrgId      the organisation that owns the catalog listing (null when catalog does not say)
     * @param sellerBehaviour how the owner sells the item (sold_heated, heated_by_seller, utensils_provided,
     *                        seller_is_bakery) from {@code product.listing.sellerBehaviour}; empty when the listing
     *                        has none. It belongs to the owner's listing only: never assume it for another seller.
     */
    record Facts(String variantAtin, String productType, boolean perishable, String category, String ownerOrgId,
                 Map<String, Object> sellerBehaviour) {

        public Facts {
            sellerBehaviour = sellerBehaviour == null ? Map.of() : Map.copyOf(sellerBehaviour);
        }

        /** Facts without listing behaviour (tests). */
        public Facts(String variantAtin, String productType, boolean perishable, String category) {
            this(variantAtin, productType, perishable, category, null, Map.of());
        }

        /** The behaviour that applies to a line sold by {@code sellerOrgId}: the owner's own, else none. */
        public Map<String, Object> behaviourFor(String sellerOrgId) {
            return ownerOrgId != null && ownerOrgId.equals(sellerOrgId) ? sellerBehaviour : Map.of();
        }
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
