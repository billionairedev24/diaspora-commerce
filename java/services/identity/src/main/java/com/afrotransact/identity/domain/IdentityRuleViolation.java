package com.afrotransact.identity.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** A sign-up, credential or access rule was broken; carries per-field messages for forms (HTTP 422). */
public class IdentityRuleViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient Map<String, String> fieldErrors;

    public IdentityRuleViolation(String code, String message) {
        this(code, message, Map.of());
    }

    public IdentityRuleViolation(String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = Map.copyOf(new LinkedHashMap<>(fieldErrors));
    }

    /** Stable machine-readable code, for example {@code identity.invalid-sign-up}. */
    public String code() {
        return code;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
