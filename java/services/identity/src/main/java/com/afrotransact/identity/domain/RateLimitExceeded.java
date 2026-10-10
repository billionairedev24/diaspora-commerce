package com.afrotransact.identity.domain;

import java.time.Duration;

/** A throttled action (code resend, SMS challenge) was attempted too soon (HTTP 429). */
public class RateLimitExceeded extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final Duration retryAfter;

    public RateLimitExceeded(String code, String message, Duration retryAfter) {
        super(message);
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public String code() {
        return code;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
