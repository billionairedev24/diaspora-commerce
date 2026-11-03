package com.afrotransact.identity.application;

import java.time.Instant;

/**
 * Re-authentication for the most dangerous identity admin actions (S291). Both methods need {@code identity:admin}.
 * A staff member proves who they are again by entering their password ({@link #verify}, which returns a proof for the
 * {@code X-Step-Up} header) or by having signed in within the last five minutes; {@link #require} accepts either.
 */
public interface StepUpService {

    /**
     * Checks the signed-in staff member's password and issues a proof valid for five minutes, bound to them. Wrong
     * passwords are counted by the sign-in brute-force protection (the account locks after repeated failures).
     */
    StepUpProof verify(String password);

    /**
     * Passes when {@code signedInAt} (the access token's {@code auth_time}) is within the last five minutes or
     * {@code proof} is an unexpired proof of this user; otherwise throws 403 {@code identity.step-up-required}.
     */
    void require(String proof, Instant signedInAt);

    /** @param proof opaque value for the {@code X-Step-Up} header; shown once, only its hash is stored */
    record StepUpProof(String proof, Instant expiresAt) {
    }
}
