package com.afrotransact.fulfilment.domain;

import java.time.Instant;

/**
 * Last known access state of a driver.
 *
 * @param driverId  identity subject
 * @param status    active or revoked
 * @param changedAt when the grant or revocation happened
 * @param reason    why (role revoked, permission revoked, account disabled, dispatch suspension)
 * @param source    who changed it
 */
public record DriverAccess(String driverId, DriverAccessStatus status, Instant changedAt, String reason,
                           AccessSource source) {

    /**
     * The app must sign out: revoked, and the token predates the revocation (identity revocations take effect on the
     * next token) or a dispatcher suspended the driver (only a dispatcher lifts that).
     */
    public boolean revokedFor(Instant tokenIssuedAt) {
        return status == DriverAccessStatus.REVOKED && (source == AccessSource.DISPATCH || tokenIssuedAt == null
                || !tokenIssuedAt.isAfter(changedAt));
    }

    /**
     * Revocation never loses work: events captured on the device up to the revocation are still accepted; later
     * ones are refused.
     */
    public boolean acceptsEventCapturedAt(Instant capturedAt) {
        return status == DriverAccessStatus.ACTIVE || capturedAt != null && !capturedAt.isAfter(changedAt);
    }
}
