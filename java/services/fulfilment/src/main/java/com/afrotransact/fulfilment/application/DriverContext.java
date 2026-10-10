package com.afrotransact.fulfilment.application;

import com.afrotransact.fulfilment.domain.DriverAccess;

/**
 * Resolved driver of a request.
 *
 * @param driverId identity subject
 * @param access   last known access state
 * @param signOut  the app must sign out (access revoked); queued events captured before the revocation still sync
 */
public record DriverContext(String driverId, DriverAccess access, boolean signOut) {
}
