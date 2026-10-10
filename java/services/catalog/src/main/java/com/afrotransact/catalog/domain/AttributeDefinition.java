package com.afrotransact.catalog.domain;

import java.util.List;
import java.util.Objects;

/**
 * A detail shoppers need for a product type (e.g. material, allergens, MoCRA number).
 *
 * @param key       stable key stored on products
 * @param label     display label
 * @param options   allowed values; empty means free text
 * @param required  must be filled before submission
 * @param multiline long text (ingredients, INCI)
 */
public record AttributeDefinition(String key, String label, List<String> options, boolean required,
                                  boolean multiline) {

    public AttributeDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        options = options == null ? List.of() : List.copyOf(options);
    }

    public boolean accepts(String value) {
        if (value == null || value.isBlank()) {
            return !required;
        }
        return options.isEmpty() || options.contains(value);
    }
}
