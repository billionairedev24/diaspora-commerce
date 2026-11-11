package com.afrotransact.orders.domain;

/** Where a delivery quote request is. */
public enum QuoteStatus {
    /** Asked; nobody on staff holds it. */
    REQUESTED("Requested"),
    /** A staff member holds it and is working out the amount. */
    IN_PROGRESS("Being quoted"),
    /** An amount is agreed and waits for the shopper (valid until {@code expiresAt}). */
    READY("Quote ready"),
    DECLINED("Declined"),
    /** The agreed amount was not used in time. */
    EXPIRED("Expired"),
    /** An order carries the agreed amount. */
    USED("Used");

    private final String label;

    QuoteStatus(String label) {
        this.label = label;
    }

    /** The plain word the shopper and staff see. */
    public String label() {
        return label;
    }

    /** With staff, not yet answered. */
    public boolean awaitingStaff() {
        return this == REQUESTED || this == IN_PROGRESS;
    }

    /** Still alive: someone may yet act on it. */
    public boolean live() {
        return this == REQUESTED || this == IN_PROGRESS || this == READY;
    }
}
