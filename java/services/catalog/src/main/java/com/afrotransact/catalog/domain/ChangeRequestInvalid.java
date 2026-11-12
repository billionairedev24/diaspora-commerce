package com.afrotransact.catalog.domain;

import java.util.Map;

/** The request does not fit its type (HTTP 422 {@code catalog.change-request-invalid}); {@code errors} says why, by field. */
public class ChangeRequestInvalid extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Map<String, String> errors;

    public ChangeRequestInvalid(String message, Map<String, String> errors) {
        super(message);
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> errors() {
        return errors;
    }
}
