package com.afrotransact.orders.application;

import java.util.EnumSet;
import java.util.Set;

import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;

/**
 * The statuses of an AfroTransact-delivered shipment that people at the staff console set with the delivery steps (the
 * Delivery module, {@code delivery:manage}), which carry the proof and the audit trail. The general status change refuses
 * them for people, and the order sheet gets them from the delivery steps, so both screens always agree.
 */
final class DeliverySteps {

    private static final Set<ShipmentStatus> STEPS = EnumSet.of(ShipmentStatus.PICKED_UP, ShipmentStatus.IN_TRANSIT,
            ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERED, ShipmentStatus.DELIVERY_FAILED);

    private DeliverySteps() {
    }

    /**
     * True when moving {@code s} to {@code to} is a delivery step. Returned is one while the parcel is on its way (only
     * after a failed attempt is it legal; a delivered order is returned through Returns and orders:manage).
     */
    static boolean isStep(Shipment s, ShipmentStatus to) {
        return s.fulfilmentMode().staffDelivers() && (STEPS.contains(to) || to == ShipmentStatus.RETURNED
                && s.status() != ShipmentStatus.DELIVERED);
    }
}
