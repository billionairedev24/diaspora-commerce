package com.afrotransact.identity.domain;

import java.time.Instant;

/**
 * An active sign-in (an OAuth authorization holding a refresh token) on some device or app.
 *
 * @param current true for the session that issued the caller's access token
 */
public record UserSession(String id, String clientId, String clientName, Instant signedInAt, Instant lastActiveAt,
                          Instant expiresAt, boolean current) {
}
