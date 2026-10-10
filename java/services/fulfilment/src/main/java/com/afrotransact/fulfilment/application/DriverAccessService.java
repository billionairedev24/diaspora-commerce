package com.afrotransact.fulfilment.application;

import java.util.UUID;

import com.afrotransact.fulfilment.domain.DriverAccess;

/**
 * Driver access (S081): only callers with {@code delivery:operate} use the driver app. Revocations come from
 * identity-service (Driver role or direct permission removed, account disabled) or from a dispatcher's suspension;
 * the app is told to sign out, and events captured on the device before the revocation are still accepted.
 */
public interface DriverAccessService {

    /** The sign-in access check. Throws {@link DriverAccessDenied} without access. */
    DriverSession check(DriverCredential credential);

    /** Resolves the driver of a sync request; a revoked driver resolves with {@code signOut} (never-granted throws). */
    DriverContext resolve(DriverCredential credential);

    /** Applies an identity-service access change (inbox-deduplicated). @return true when the driver state changed */
    boolean onIdentityChange(UUID eventId, IdentityAccessChange change);

    /** Dispatch suspension (delivery:dispatch). */
    DriverAccess suspend(String driverId, String reason);

    DriverAccess reinstate(String driverId);
}
