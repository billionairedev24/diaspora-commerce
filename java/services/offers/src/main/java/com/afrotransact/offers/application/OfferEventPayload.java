package com.afrotransact.offers.application;

import java.util.UUID;

import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.ShipFrom;

/**
 * Payload of {@code offers.offer.created|updated|withdrawn}; indexer, cart, seller center and Lola consume it.
 * {@code inventorySku} (S257) lets inventory tell which FBA stock has a live Fulfilled-by-AfroTransact offer.
 */
public record OfferEventPayload(UUID offerId, String atin, String productAtin, String sellerOrgId, String sellerKind,
                                String sellerSku, String condition, long priceMinor, Long listPriceMinor,
                                long shippingMinor, long landedPriceMinor, String currency, String fulfilment,
                                int handlingDays, ShipFrom shipFrom, String status, boolean productLive,
                                String inventorySku) {

    static OfferEventPayload of(Offer o) {
        return new OfferEventPayload(o.id(), o.atin(), o.productAtin(), o.sellerOrgId(), o.sellerKind().name(),
                o.sellerSku(), o.condition().name(), o.priceMinor(), o.listPriceMinor(), o.shippingMinor(),
                o.landedPriceMinor(), o.currency(), o.fulfilment().name(), o.handlingDays(), o.shipFrom(),
                o.status().name(), o.productLive(), o.inventorySku());
    }
}
