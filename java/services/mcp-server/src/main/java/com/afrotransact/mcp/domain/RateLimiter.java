package com.afrotransact.mcp.domain;

import java.time.Duration;

/** Per-key rate limiting of tool calls. */
public interface RateLimiter {

    Decision tryAcquire(String key);

    /**
     * Outcome of one acquire.
     *
     * @param allowed    a token was taken
     * @param remaining  whole tokens left
     * @param retryAfter how long until the next token (zero when allowed)
     */
    record Decision(boolean allowed, long remaining, Duration retryAfter) {
    }
}
