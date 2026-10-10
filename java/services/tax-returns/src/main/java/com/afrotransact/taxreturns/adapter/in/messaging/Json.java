package com.afrotransact.taxreturns.adapter.in.messaging;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** Lenient readers for event payload fields. */
final class Json {

    private Json() {
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    static UUID uuid(JsonNode n, String field) {
        String s = text(n, field);
        return s == null ? null : UUID.fromString(s);
    }

    static Instant instant(JsonNode n, String field) {
        return parse(text(n, field));
    }

    static Instant parse(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
