package com.afrotransact.deliveryruns.domain;

/** Status of a pre-order in the window's order list. */
public enum PreorderOrderStatus {
    PAID("Paid"),
    /** Added by customer service after (or during) the window. */
    LATE_ADD("Late add"),
    CANCELLED("Cancelled");

    private final String label;

    PreorderOrderStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Paid and late adds count as pre-orders; cancelled ones do not. */
    public boolean counts() {
        return this != CANCELLED;
    }
}
