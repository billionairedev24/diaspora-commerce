package com.afrotransact.trustops.domain;

import java.util.Map;

/** Small text checks shared by the aggregates. */
final class Texts {

    private Texts() {
    }

    static String required(String value, String field, String label, int max) {
        if (value == null || value.isBlank()) {
            throw new TrustRuleViolation("trustops.field-required", label + " is required", Map.of(field, "Required"));
        }
        return bounded(value, field, label, max);
    }

    static String optional(String value, String field, String label, int max) {
        return value == null || value.isBlank() ? null : bounded(value, field, label, max);
    }

    private static String bounded(String value, String field, String label, int max) {
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new TrustRuleViolation("trustops.field-too-long", label + " is longer than " + max + " characters",
                    Map.of(field, "At most " + max + " characters"));
        }
        return trimmed;
    }
}
