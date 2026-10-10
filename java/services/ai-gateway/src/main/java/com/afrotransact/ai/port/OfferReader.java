package com.afrotransact.ai.port;

import java.util.List;

/**
 * offers-service {@code GET /v1/public/products/{atin}/offers}. Returns an empty list while the offers service is not
 * deployed or does not answer, so compare_offers degrades to the catalog view.
 */
public interface OfferReader {

    List<Offer> offers(String productAtin, String bearerToken);

    /** One seller's offer for a product. */
    record Offer(String offerId, String sellerName, String sellerKind, String fulfilment, long priceMinor,
                 String currency, boolean inStock, int deliveryDays, String condition) {
    }
}
