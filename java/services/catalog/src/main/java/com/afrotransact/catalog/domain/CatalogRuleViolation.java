package com.afrotransact.catalog.domain;

import java.util.List;
import java.util.Map;

/** Thrown when a product or request breaks a catalog rule; carries per-field messages for the form. */
public class CatalogRuleViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Map<String, String> fieldErrors;

    public CatalogRuleViolation(String message) {
        this(message, Map.of());
    }

    public CatalogRuleViolation(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }

    public List<String> fields() {
        return fieldErrors.keySet().stream().sorted().toList();
    }
}
