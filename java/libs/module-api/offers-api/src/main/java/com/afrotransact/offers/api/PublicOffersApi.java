package com.afrotransact.offers.api;

import java.util.List;

/**
 * The anonymous storefront read of a product's offers that the AI shopping agent (Lola) makes for "compare offers":
 * {@code GET /v1/public/products/{productAtin}/buy-boxes}, flattened to one row per listed offer (winner first). No scope is
 * needed over HTTP, so none is needed here; the call still runs under an in-process caller principal.
 */
public interface PublicOffersApi {

    /** Every listed offer of every variant of the product page, buy box winners first; empty when there are none. */
    List<OfferRow> offersForProduct(String productAtin, String currency);

    /**
     * @param inStock      false only when the offer is out of stock
     * @param deliveryDays handling plus transit estimate the buy box uses
     */
    record OfferRow(String offerId, String sellerName, String sellerKind, String fulfilment, long priceMinor,
                       String currency, boolean inStock, int deliveryDays, String condition) {
    }
}
