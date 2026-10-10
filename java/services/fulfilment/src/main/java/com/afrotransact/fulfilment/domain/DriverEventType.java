package com.afrotransact.fulfilment.domain;

import java.util.Locale;
import java.util.Optional;

/** Event types the driver app queues in its on-device outbox and syncs in order. */
public enum DriverEventType {
    SESSION_SIGNED_IN("session.signed_in"),
    INSPECTION_SUBMITTED("inspection.submitted"),
    LOADOUT_SCANNED("loadout.scanned"),
    LOADOUT_COMPLETED("loadout.completed"),
    ROUTE_STARTED("route.started"),
    STOP_ARRIVED("stop.arrived"),
    STOP_SCANNED("stop.scanned"),
    PICKUP_CONFIRMED("pickup.confirmed"),
    PICKUP_PROBLEM("pickup.problem"),
    CUSTOMER_CONTACTED("customer.contacted"),
    DELIVERY_COMPLETED("delivery.completed"),
    DELIVERY_PROBLEM("delivery.problem"),
    RETURN_SCANNED("return.scanned"),
    RETURN_PICKUP_COLLECTED("return_pickup.collected"),
    RETURN_PICKUP_FAILED("return_pickup.failed"),
    SHIFT_ENDED("shift.ended"),
    SOS_RAISED("sos.raised");

    private final String wire;

    DriverEventType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<DriverEventType> fromWire(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        for (DriverEventType t : values()) {
            if (t.wire.equals(v)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
