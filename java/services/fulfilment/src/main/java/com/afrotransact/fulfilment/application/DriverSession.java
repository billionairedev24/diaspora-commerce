package com.afrotransact.fulfilment.application;

import java.time.Instant;
import java.util.List;

/**
 * Result of the driver app's access check after sign-in.
 *
 * @param driverId    identity subject
 * @param access      ACTIVE
 * @param permissions effective permissions of the token
 * @param routeIds    open routes assigned to the driver
 * @param checkedAt   server time
 */
public record DriverSession(String driverId, String access, List<String> permissions, List<String> routeIds,
                            Instant checkedAt) {
}
