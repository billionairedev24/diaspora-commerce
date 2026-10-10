package com.afrotransact.inventory.domain;

/** Lifecycle of a lot. Only SELLABLE lots count towards on-hand; held statuses count towards damaged. */
public enum LotStatus {
    SELLABLE,
    QUARANTINE,
    EXPIRED,
    WRITTEN_OFF,
    DAMAGED;

    /** Physically present but blocked from sale (counted in the position's {@code damaged} bucket). */
    public boolean held() {
        return this == QUARANTINE || this == EXPIRED || this == DAMAGED;
    }
}
