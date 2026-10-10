package com.afrotransact.bookings.domain;

import java.util.Map;

/** A booking, service or availability rule was broken (HTTP 422); carries per-field messages for forms. */
public class BookingsRuleViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient Map<String, String> fieldErrors;

    public BookingsRuleViolation(String code, String message) {
        this(code, message, Map.of());
    }

    public BookingsRuleViolation(String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    /** Stable code, for example {@code bookings.slot-unavailable}. */
    public String code() {
        return code;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
