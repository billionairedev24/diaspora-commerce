package com.afrotransact.deliveryruns.domain;

/** Why customer service added an order late (the back office's reasons, plus the design's driver report / other). */
public enum LateAddReason {
    CUSTOMER_CALLED("Customer called"),
    LIVE_CHAT("Live chat"),
    PAYMENT_FAILED("Payment failed at close"),
    GOODWILL("Goodwill"),
    DRIVER_REPORT("Driver report"),
    OTHER("Other");

    private final String label;

    LateAddReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
