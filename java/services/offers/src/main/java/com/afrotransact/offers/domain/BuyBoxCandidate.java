package com.afrotransact.offers.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything the buy box policy needs about one offer, already resolved: live state, available units (inventory
 * projection for Fulfilled by AfroTransact, seller quantity otherwise), delivery estimate and seller standing.
 */
public record BuyBoxCandidate(UUID offerId, String sellerOrgId, SellerKind sellerKind, OfferCondition condition,
                              long priceMinor, long shippingMinor, String currency, Fulfilment fulfilment,
                              int availableQty, int deliveryDays, boolean active, boolean productLive,
                              SellerStanding standing, Instant createdAt) {

    public BuyBoxCandidate {
        Objects.requireNonNull(offerId, "offerId");
        Objects.requireNonNull(standing, "standing");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public long landedPriceMinor() {
        return priceMinor + shippingMinor;
    }
}
