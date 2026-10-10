package com.afrotransact.trustops.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * What an orders event says about one shipment; null fields leave the stored value unchanged.
 *
 * @param cancelledBy actor type of a cancellation (SELLER, SYSTEM, CUSTOMER, PLATFORM_ADMIN …)
 */
public record ShipmentFact(String shipmentId, String orderId, String sellerOrgId, Instant placedAt,
                           LocalDate promisedBy, Instant deliveredAt, Instant cancelledAt, String cancelledBy,
                           String status) {

    public ShipmentFact {
        Objects.requireNonNull(shipmentId, "shipmentId");
        Objects.requireNonNull(sellerOrgId, "sellerOrgId");
    }
}
