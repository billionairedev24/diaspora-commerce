package com.afrotransact.taxreturns.domain.returns;

import java.util.List;
import java.util.Map;

/** A return or refund request breaks a rule; carries a stable code and per-field messages (HTTP 422). */
public class ReturnRuleViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient Map<String, String> fieldErrors;

    public ReturnRuleViolation(String code, String message) {
        this(code, message, Map.of());
    }

    public ReturnRuleViolation(String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public String code() {
        return code;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }

    public List<String> fields() {
        return fieldErrors.keySet().stream().sorted().toList();
    }
}
