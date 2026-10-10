package com.afrotransact.retail.domain;

/**
 * Purchase order lifecycle (design): Draft → Pending approval (submitted) → Approved → Sent to supplier → Confirmed
 * → Partially received → Received → Closed, or Cancelled until a shipment is booked.
 */
public enum PoStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    SENT,
    CONFIRMED,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CLOSED,
    CANCELLED;

    public boolean receivable() {
        return this == SENT || this == CONFIRMED || this == PARTIALLY_RECEIVED;
    }
}
