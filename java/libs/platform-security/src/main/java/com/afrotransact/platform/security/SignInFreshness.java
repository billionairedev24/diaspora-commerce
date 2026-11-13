package com.afrotransact.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The step-up rule of identity (S291) for services that hold no password: an action passes when the access token's
 * {@code auth_time} claim (when the person last signed in, kept across refreshes) is within {@code maxAge}. Otherwise
 * 403 {@code auth.step-up-required}: the client sends the person through a fresh sign-in
 * ({@code /v1/auth/login?prompt=login}) and retries. A token without {@code auth_time} counts as stale (fail closed).
 * Calls that are not JWT-authenticated (security off in the local profile) pass.
 */
public final class SignInFreshness {

    public static final String CODE = "auth.step-up-required";
    public static final String AUTH_TIME = "auth_time";

    private SignInFreshness() {
    }

    /** True when the current token signed in within {@code maxAge} (always true outside a JWT call). */
    public static boolean isFresh(Duration maxAge, Clock clock) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            return true;
        }
        Jwt jwt = token.getToken();
        Instant signedIn = jwt.getClaimAsInstant(AUTH_TIME);
        return signedIn != null && !signedIn.plus(maxAge).isBefore(clock.instant());
    }

    /** Passes when {@link #isFresh}; otherwise 403 {@link #CODE} with {@code what} in the message. */
    public static void require(Duration maxAge, Clock clock, String what) {
        if (!isFresh(maxAge, clock)) {
            throw new ForbiddenActionException(CODE, "Confirm it is you: sign in again to " + what + " (your last "
                    + "sign-in was more than " + minutes(maxAge) + " minutes ago)");
        }
    }

    private static long minutes(Duration d) {
        return Math.max(1, d.toMinutes());
    }
}
