package com.afrotransact.identity.domain;

import java.time.Instant;

/**
 * Failure counter of one throttle key.
 *
 * @param failures        failed attempts in the current window
 * @param windowStartedAt start of the counting window
 * @param lockedUntil     end of the current lockout, or null
 * @param lockouts        consecutive lockouts (the lockout doubles each time, reset by a success or a quiet day)
 */
public record ThrottleState(ThrottleScope scope, String key, int failures, Instant windowStartedAt,
                            Instant lockedUntil, int lockouts) {

    public static ThrottleState fresh(ThrottleScope scope, String key, Instant now) {
        return new ThrottleState(scope, key, 0, now, null, 0);
    }

    public boolean lockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
