package com.afrotransact.bookings.domain;

/** Cancellation policy families offered in Provider Hub. */
public enum PolicyKind {
    FLEXIBLE("Flexible"),
    MODERATE("Moderate"),
    STRICT("Strict"),
    CUSTOM("Custom");

    private final String label;

    PolicyKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
