package com.afrotransact.identity.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Step-up (re-authentication) for the most dangerous identity admin actions (S291): rotating the signing key, signing
 * a user or a whole client out, disabling a client and changing the authentication settings. The staff member has
 * proved who they are again if they signed in within {@link #FRESH_SIGN_IN}, or hold a proof issued within
 * {@link #PROOF_TTL} after re-entering their password.
 */
public final class StepUp {

    /** How long a proof issued after a password check stays valid. */
    public static final Duration PROOF_TTL = Duration.ofMinutes(5);
    /** Default: a sign-in this recent counts as a fresh authentication (access token {@code auth_time}). */
    public static final Duration FRESH_SIGN_IN = Duration.ofMinutes(5);
    /** Tolerated clock difference for an {@code auth_time} slightly in the future. */
    private static final Duration SKEW = Duration.ofSeconds(30);

    private StepUp() {
    }

    /** True when the sign-in time is known, not in the future (beyond skew) and younger than {@code window}. */
    public static boolean freshSignIn(Instant signedInAt, Instant now, Duration window) {
        return signedInAt != null && !signedInAt.isAfter(now.plus(SKEW)) && signedInAt.plus(window).isAfter(now);
    }
}
