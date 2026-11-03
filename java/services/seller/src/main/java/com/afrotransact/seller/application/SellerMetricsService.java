package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Records the metrics reward rules read from other services' events (inbox-deduplicated on the event id), then
 * re-evaluates the organisation's rules on that metric.
 */
public interface SellerMetricsService {

    /** orders.shipment.delivered: gross sales (USD) and one completed order. Returns false for a duplicate. */
    boolean shipmentDelivered(UUID eventId, String orgId, long totalMinor, String currency, Instant at);

    /** booking.completed: one completed booking. */
    boolean bookingCompleted(UUID eventId, String orgId, Instant at);

    /** offers.offer.created / updated / withdrawn: whether the offer is a live listing now. */
    boolean offerChanged(UUID eventId, String offerId, String orgId, boolean live, Instant at);
}
