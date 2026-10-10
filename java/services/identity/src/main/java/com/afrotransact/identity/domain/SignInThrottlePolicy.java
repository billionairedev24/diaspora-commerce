package com.afrotransact.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Brute-force protection: counts failed sign-in attempts per account and per IP and decides temporary lockouts. */
public interface SignInThrottlePolicy {

    /** Remaining lockout, if the key is locked at {@code now}. */
    Optional<Duration> lockedFor(ThrottleState state, Instant now);

    /** Counts a failure; the returned state is locked when the limit was reached. */
    ThrottleState onFailure(ThrottleState state, Instant now);

    /** A successful sign-in clears the account counter; IP counters only expire (a valid login must not reset them). */
    ThrottleState onSuccess(ThrottleState state, Instant now);
}
