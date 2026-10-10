package com.afrotransact.orders.domain;

import java.util.Locale;

/**
 * Shipment state machine (architecture §17c): placed → confirmed → packed → ready → picked_up → in_transit →
 * out_for_delivery → delivered, plus delivery_failed, returned and cancelled. {@code PENDING_PAYMENT} precedes
 * {@code PLACED} while the checkout saga waits for the payment authorisation.
 */
public enum ShipmentStatus {
    PENDING_PAYMENT(0),
    PLACED(1),
    CONFIRMED(2),
    PACKED(3),
    READY(4),
    PICKED_UP(5),
    IN_TRANSIT(6),
    OUT_FOR_DELIVERY(7),
    DELIVERY_FAILED(8),
    DELIVERED(9),
    RETURNED(9),
    CANCELLED(9);

    private final int rank;

    ShipmentStatus(int rank) {
        this.rank = rank;
    }

    /** Progress order used to keep out-of-order carrier events from moving a shipment backwards. */
    public int rank() {
        return rank;
    }

    public boolean terminal() {
        return this == DELIVERED || this == RETURNED || this == CANCELLED;
    }

    /** The parcel has left the seller / fulfilment centre (capture point for carrier shipments). */
    public boolean handedOver() {
        return rank >= PICKED_UP.rank && this != CANCELLED;
    }

    /** Lower-case wire name (placed, picked_up, …) used in events and the API. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
