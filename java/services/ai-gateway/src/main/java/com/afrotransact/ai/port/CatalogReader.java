package com.afrotransact.ai.port;

import java.util.Map;
import java.util.Optional;

/** catalog-service public reads: {@code GET /v1/public/products/{atin}} and {@code /v1/public/variants/{atin}}. */
public interface CatalogReader {

    /** A live product or variant by ATIN (variant first, then product); empty when not live or unknown. */
    Optional<Item> find(String atin, String bearerToken);

    /**
     * A live catalog item.
     *
     * @param atin        the ATIN asked for
     * @param productAtin product ATIN
     * @param title       title (seller text)
     * @param attributes  product attributes (seller text)
     * @param priceMinor  variant price in minor units when the catalog carries one (0 otherwise)
     * @param currency    currency
     * @param sellerOrgId owning organisation
     */
    record Item(String atin, String productAtin, String title, Map<String, String> attributes, long priceMinor,
                String currency, String sellerOrgId) {

        public Item {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
