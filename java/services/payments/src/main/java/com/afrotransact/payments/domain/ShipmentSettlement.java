package com.afrotransact.payments.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Settlement hold of one shipment of a multi-shipment order: the seller net captured for that shipment becomes
 * transferable {@code hold} after the shipment's own delivery, without waiting for the order's other shipments.
 * The order-level {@link Settlement} later transfers whatever payable is left.
 */
public record ShipmentSettlement(String orderId, String shipmentId, SettlementKind kind, Instant deliveredAt,
                                 Instant releaseAt, SettlementStatus status, Instant updatedAt) {

    public ShipmentSettlement {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(shipmentId, "shipmentId");
        Objects.requireNonNull(deliveredAt, "deliveredAt");
        Objects.requireNonNull(releaseAt, "releaseAt");
    }

    public static ShipmentSettlement delivered(String orderId, String shipmentId, SettlementKind kind,
                                               Instant deliveredAt, Duration hold, Instant now) {
        Settlement order = Settlement.delivered(orderId, kind, deliveredAt, hold, now);
        return new ShipmentSettlement(orderId, shipmentId, order.kind(), order.deliveredAt(), order.releaseAt(),
                SettlementStatus.OPEN, now.truncatedTo(ChronoUnit.MICROS));
    }
}
