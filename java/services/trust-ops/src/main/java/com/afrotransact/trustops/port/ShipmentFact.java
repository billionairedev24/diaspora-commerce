package com.afrotransact.trustops.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * What an orders or fulfilment event says about one shipment; null fields leave the stored value unchanged.
 *
 * @param cancelledBy  actor type of a cancellation (SELLER, SYSTEM, CUSTOMER, PLATFORM_ADMIN …)
 * @param shipBy       date the seller must hand the shipment over by (orders.order.placed)
 * @param handedOverAt when the shipment left the seller (fulfilment.shipment.handed_over, or orders picked_up)
 */
public record ShipmentFact(String shipmentId, String orderId, String sellerOrgId, Instant placedAt,
                           LocalDate promisedBy, Instant deliveredAt, Instant cancelledAt, String cancelledBy,
                           String status, LocalDate shipBy, Instant handedOverAt) {

    public ShipmentFact {
        Objects.requireNonNull(shipmentId, "shipmentId");
        Objects.requireNonNull(sellerOrgId, "sellerOrgId");
    }

    /** A fact without ship-by or handover details. */
    public ShipmentFact(String shipmentId, String orderId, String sellerOrgId, Instant placedAt,
                        LocalDate promisedBy, Instant deliveredAt, Instant cancelledAt, String cancelledBy,
                        String status) {
        this(shipmentId, orderId, sellerOrgId, placedAt, promisedBy, deliveredAt, cancelledAt, cancelledBy, status,
                null, null);
    }
}
