package com.afrotransact.seller.domain;

import java.util.Map;

/** A normalised value plus field errors keyed {@code section.field} (empty when valid). */
public record Validated<T>(T value, Map<String, String> errors) {

    public Validated {
        errors = Map.copyOf(errors);
    }

    public boolean ok() {
        return errors.isEmpty();
    }
}
