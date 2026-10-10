package com.afrotransact.orders.domain;

import java.util.Collection;

/** Order-level status, derived from its shipments once the order is placed. */
public enum OrderStatus {
    /** Saga is waiting for the payment authorisation. */
    PENDING_PAYMENT,
    /** Paid (authorised); no shipment has moved yet. */
    PLACED,
    /** At least one shipment is being prepared or is on its way. */
    IN_PROGRESS,
    /** Every shipment that was not cancelled reached delivered or returned. */
    COMPLETED,
    /** Checkout failed / voided, or every shipment was cancelled. */
    CANCELLED;

    /** Derives the status of a placed order from its shipments. */
    public static OrderStatus derive(Collection<ShipmentStatus> shipments) {
        if (shipments.isEmpty() || shipments.stream().allMatch(s -> s == ShipmentStatus.CANCELLED)) {
            return CANCELLED;
        }
        if (shipments.stream().anyMatch(s -> s == ShipmentStatus.PENDING_PAYMENT)) {
            return PENDING_PAYMENT;
        }
        if (shipments.stream().allMatch(ShipmentStatus::terminal)) {
            return COMPLETED;
        }
        if (shipments.stream().allMatch(s -> s == ShipmentStatus.PLACED || s == ShipmentStatus.CANCELLED)) {
            return PLACED;
        }
        return IN_PROGRESS;
    }
}
