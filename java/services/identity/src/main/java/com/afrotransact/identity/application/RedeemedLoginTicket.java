package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.identity.domain.SessionDevice;
import com.afrotransact.identity.domain.SignInMethod;

/**
 * A login ticket that was valid when burned.
 *
 * @param authenticatedAt when the sign-in completed (the ID token's {@code auth_time})
 * @param device          the end user's browser or app the ticket was issued to, when the BFF reported it
 */
public record RedeemedLoginTicket(UUID userId, SignInMethod method, Instant authenticatedAt, SessionDevice device) {

    public RedeemedLoginTicket(UUID userId, SignInMethod method, Instant authenticatedAt) {
        this(userId, method, authenticatedAt, null);
    }
}
