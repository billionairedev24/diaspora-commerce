package com.afrotransact.content.domain;

import java.util.Map;

/** A content rule was broken (HTTP 422); carries a stable code and per-field messages for back-office forms. */
public class ContentRuleViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient Map<String, String> fieldErrors;

    public ContentRuleViolation(String code, String message) {
        this(code, message, Map.of());
    }

    public ContentRuleViolation(String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    /** Throws when {@code errors} is not empty. */
    public static void throwIfAny(String code, String message, Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new ContentRuleViolation(code, message, errors);
        }
    }

    public String code() {
        return code;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
