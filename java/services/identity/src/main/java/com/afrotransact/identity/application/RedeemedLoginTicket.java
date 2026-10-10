package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.identity.domain.SignInMethod;

/**
 * A login ticket that was valid when burned.
 *
 * @param authenticatedAt when the sign-in completed (the ID token's {@code auth_time})
 */
public record RedeemedLoginTicket(UUID userId, SignInMethod method, Instant authenticatedAt) {
}
