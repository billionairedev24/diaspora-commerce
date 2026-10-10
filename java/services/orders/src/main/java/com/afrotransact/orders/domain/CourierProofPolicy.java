package com.afrotransact.orders.domain;

/** Validates the proof the driver app attaches to a courier event. */
public interface CourierProofPolicy {

    /**
     * Throws {@link OrdersRuleViolation} when the proof is not enough for moving {@code shipment} to {@code to}.
     */
    void check(Shipment shipment, ShipmentStatus to, CourierProof proof);
}
