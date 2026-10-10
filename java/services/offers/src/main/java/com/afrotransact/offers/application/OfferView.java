package com.afrotransact.offers.application;

import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.StockStatus;

/**
 * An offer with what the storefront needs around it.
 *
 * @param availableQty sellable units now (inventory projection for Fulfilled by AfroTransact, else seller quantity)
 * @param deliveryDays handling time + transit estimate used by the buy box (the BFF shows promise-service dates)
 * @param suppressed   hidden by seller enforcement or Trust &amp; Safety restriction
 */
public record OfferView(Offer offer, String sellerName, int availableQty, StockStatus stockStatus, int deliveryDays,
                        boolean suppressed) {
}
