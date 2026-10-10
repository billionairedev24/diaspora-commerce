package com.afrotransact.deliveryruns.domain;

/** A business rule of locations, runs or subscriptions was violated (HTTP 422). */
public class RuleViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public RuleViolation(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Stable code, for example {@code delivery-runs.invalid-window}. */
    public String code() {
        return code;
    }
}
