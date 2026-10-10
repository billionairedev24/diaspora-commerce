package com.afrotransact.inventory.domain;

/**
 * Lifecycle of an inbound shipment: {@code WORKING} (plan, editable; boxes and labels) → {@code SHIPPED} (in transit;
 * expected units count as inbound) → {@code RECEIVING} (the FC posted a first receipt) → {@code CLOSED} (remaining
 * expected units cancelled, discrepancies reported). {@code CANCELLED} before receiving starts.
 */
public enum InboundStatus {
    WORKING,
    SHIPPED,
    RECEIVING,
    CLOSED,
    CANCELLED;

    public boolean open() {
        return this != CLOSED && this != CANCELLED;
    }
}
