package com.afrotransact.bookings.domain;

/** Booking lifecycle (statuses and labels from the design's booking engine). */
public enum BookingStatus {
    /** Slot held while the customer confirms the deposit with their card. */
    PENDING_PAYMENT("Awaiting payment", true),
    /** Deposit authorised; waiting for the provider (expires after the request SLA). */
    REQUESTED("Awaiting provider", true),
    CONFIRMED("Confirmed", true),
    /** The customer asked to move; the provider accepts or keeps the original time. */
    RESCHEDULE_REQUESTED("New time requested", true),
    /** The provider proposed a new time; the customer accepts or declines. */
    RESCHEDULE_PROPOSED("Provider proposed a new time", true),
    COMPLETED("Completed", false),
    NO_SHOW("No-show", false),
    DECLINED("Declined by provider", false),
    EXPIRED("Expired · not confirmed", false),
    CANCELLED_CUSTOMER("Cancelled by customer", false),
    CANCELLED_PROVIDER("Cancelled by provider", false);

    private final String label;
    private final boolean active;

    BookingStatus(String label, boolean active) {
        this.label = label;
        this.active = active;
    }

    public String label() {
        return label;
    }

    /** Active bookings hold time in the calendar. */
    public boolean isActive() {
        return active;
    }
}
