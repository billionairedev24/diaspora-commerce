package com.afrotransact.catalog.domain;

/** Where a change request stands: Waiting, Applied, or "Not applied, because ..." for the seller. */
public enum ChangeRequestStatus {
    PENDING,
    APPLIED,
    REJECTED,
    WITHDRAWN;

    public boolean open() {
        return this == PENDING;
    }
}
