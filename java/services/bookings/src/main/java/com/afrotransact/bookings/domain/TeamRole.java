package com.afrotransact.bookings.domain;

/**
 * Provider team roles (identity roles {@code provider.owner|manager|practitioner|finance}).
 * Owners and practitioners take appointments by default; managers may too when marked bookable.
 */
public enum TeamRole {
    OWNER,
    MANAGER,
    PRACTITIONER,
    FINANCE;

    /** Finance staff are view-only and never take appointments. */
    public boolean canBeBooked() {
        return this != FINANCE;
    }
}
