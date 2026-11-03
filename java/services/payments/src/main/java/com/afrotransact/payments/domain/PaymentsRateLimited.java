package com.afrotransact.payments.domain;

/** Too many attempts (HTTP 429): gift card codes are guessed at by nobody for long. */
public class PaymentsRateLimited extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final long retryAfterSeconds;

    public PaymentsRateLimited(String code, String message, long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public String code() {
        return code;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
