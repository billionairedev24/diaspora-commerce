package com.afrotransact.offers.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload of {@code offers.buybox.changed}: the winner (or none) and the headline numbers search cards and carts
 * show. Published when the winner, its price, the offer count or the lowest landed price changes.
 */
public record BuyBoxChangedPayload(String atin, String productAtin, String currency, UUID previousWinnerOfferId,
                                   UUID winnerOfferId, String winnerSellerOrgId, String winnerSellerKind,
                                   Long priceMinor, Long listPriceMinor, Long shippingMinor, Long landedPriceMinor,
                                   String fulfilment, Integer availableQty, int offerCount, Long minLandedPriceMinor,
                                   Long minPriceMinor, List<String> reasonCodes, String trigger, Instant decidedAt) {

    public BuyBoxChangedPayload {
        reasonCodes = List.copyOf(reasonCodes);
    }
}
