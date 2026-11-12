package com.afrotransact.ai.domain;

/** A caller made too many model calls in a minute (HTTP 429, code {@code ai.rate-limited}). */
public class RateLimitedException extends RuntimeException {

    public static final String CODE = "ai.rate-limited";
    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("Too many AI requests; try again in " + Math.max(retryAfterSeconds, 1) + " seconds");
        this.retryAfterSeconds = Math.max(retryAfterSeconds, 1);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
