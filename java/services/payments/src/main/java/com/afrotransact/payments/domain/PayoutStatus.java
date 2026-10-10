package com.afrotransact.payments.domain;

/** Payout request lifecycle: REQUESTED → (HELD ⇄ REQUESTED) → IN_TRANSIT (approved, Stripe payout) → PAID | FAILED. */
public enum PayoutStatus {
    REQUESTED,
    HELD,
    IN_TRANSIT,
    PAID,
    FAILED;

    /** Requested or on the way (shown as "Requested and on the way"). */
    public boolean inFlight() {
        return this == REQUESTED || this == HELD || this == IN_TRANSIT;
    }

    /** Uses part of the available balance (a failed payout gives it back). */
    public boolean consumesBalance() {
        return this != FAILED;
    }
}
