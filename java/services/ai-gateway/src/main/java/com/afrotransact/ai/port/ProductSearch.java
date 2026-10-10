package com.afrotransact.ai.port;

import java.util.List;

/** search-service {@code GET /v1/search} called with the shopper's relayed bearer token. */
public interface ProductSearch {

    List<Hit> search(Query query, String bearerToken);

    /**
     * Search parameters (a subset of the search API).
     *
     * @param text          free text
     * @param maxPriceMinor price ceiling in minor units (null = none)
     * @param inStockOnly   only sellable items
     * @param community     community filter, for example {@code Nigerian}
     * @param size          number of hits (1–24)
     */
    record Query(String text, Long maxPriceMinor, boolean inStockOnly, String community, int size) {

        public Query {
            size = Math.max(1, Math.min(size, 24));
        }
    }

    /** A search hit, field names as in contracts/openapi/search.yaml. */
    record Hit(String atin, String title, String brand, String sellerName, String sellerKind, String fulfilment,
               long priceMinor, String currency, double rating, int reviewCount, boolean inStock, boolean lowStock,
               int availableQty, int deliveryDays, String imageUrl, int offerCount) {
    }
}
