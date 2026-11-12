package com.afrotransact.ai.port;

/** Per-key call-rate limit over a sliding one-minute window. */
public interface CallRateLimiter {

    /**
     * Counts one call for the key.
     *
     * @param key            who is calling (a user or client subject)
     * @param limitPerMinute calls allowed in any 60 seconds; 0 or less = unlimited
     * @return 0 when the call is allowed, otherwise the seconds to wait before the key may call again (the call is not
     *         counted)
     */
    long acquire(String key, int limitPerMinute);
}
