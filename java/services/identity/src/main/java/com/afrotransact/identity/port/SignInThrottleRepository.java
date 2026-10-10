package com.afrotransact.identity.port;

import java.time.Instant;

import com.afrotransact.identity.domain.ThrottleScope;
import com.afrotransact.identity.domain.ThrottleState;

/** Failure counters of the brute-force protection. */
public interface SignInThrottleRepository {

    /** Current counter (a fresh one when none exists); does not lock the row. */
    ThrottleState find(ThrottleScope scope, String key, Instant now);

    /** Current counter, row-locked until the end of the caller's transaction (created when missing). */
    ThrottleState lock(ThrottleScope scope, String key, Instant now);

    void save(ThrottleState state, Instant now);
}
