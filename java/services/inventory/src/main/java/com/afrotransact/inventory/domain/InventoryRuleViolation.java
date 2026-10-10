package com.afrotransact.inventory.domain;

import java.util.Map;

/** A stock rule was broken (HTTP 422). Carries a stable code such as {@code inventory.below-reserved}. */
public class InventoryRuleViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient Map<String, String> fieldErrors;

    public InventoryRuleViolation(String code, String message) {
        this(code, message, Map.of());
    }

    public InventoryRuleViolation(String code, String message, Map<String, String> fieldErrors) {
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
}
