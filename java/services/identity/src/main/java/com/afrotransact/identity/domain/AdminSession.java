package com.afrotransact.identity.domain;

import java.time.Instant;

/**
 * One sign-in (an OAuth authorization) as the admin console lists it.
 *
 * @param ipAddress  where the sign-in came from, when the authorization recorded it
 * @param lastUsedAt when its newest token was issued
 * @param expiresAt  when its refresh token (or, without one, its access token) expires
 */
public record AdminSession(String id, String userId, String userName, String userEmail, String clientId,
                           String clientName, String ipAddress, Instant signedInAt, Instant lastUsedAt,
                           Instant expiresAt, boolean refreshable) {
}
