package com.afrotransact.trustops.domain;

import java.util.Map;

/** A trust-ops business rule was broken (HTTP 422); carries per-field messages for the staff console forms. */
public class TrustRuleViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient Map<String, String> fieldErrors;

    public TrustRuleViolation(String code, String message) {
        this(code, message, Map.of());
    }

    public TrustRuleViolation(String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    /** Stable machine-readable code, for example {@code trustops.reason-code-required}. */
    public String code() {
        return code;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
