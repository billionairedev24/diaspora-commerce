package com.afrotransact.fulfilment.domain;

/**
 * A stop collects packages from a seller, delivers to a customer, or collects a customer's return parcel (the driver
 * checks the RMA's handover code with tax-returns and brings the parcel back to the depot).
 */
public enum StopKind {
    PICKUP,
    DROPOFF,
    RETURN_PICKUP
}
