package com.afrotransact.retail.domain;

/** The nine-step inbound timeline from the design (STAGES). */
public enum ShipmentStage {
    BOOKED("PO confirmed"),
    CARGO_READY("Cargo ready"),
    PICKED_UP("Picked up at origin"),
    EXPORT_CLEARED("Export cleared"),
    DEPARTED("Departed origin"),
    ARRIVED_PORT("Arrived US port"),
    CUSTOMS_CLEARED("US customs cleared"),
    DELIVERED_TO_FC("Delivered to FC"),
    RECEIVED("Received & put away");

    private final String label;

    ShipmentStage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean atOrAfter(ShipmentStage other) {
        return ordinal() >= other.ordinal();
    }
}
