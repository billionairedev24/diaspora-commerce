package com.afrotransact.trustops.domain;

/** Queue item lifecycle. CLOSED = decided directly in catalog (or withdrawn) while it was queued here. */
public enum ModerationStatus {
    PENDING,
    ESCALATED,
    APPROVED,
    REJECTED,
    CLOSED;

    public boolean open() {
        return this == PENDING || this == ESCALATED;
    }
}
