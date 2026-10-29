package com.afrotransact.catalog.adapter.in.web;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import java.util.Map;

/** Optimistic locking over HTTP: {@code If-Match: "3"} names the version an editor loaded. */
final class IfMatch {

    private IfMatch() {
    }

    /** Parses {@code "3"}, {@code W/"3"} or {@code 3}; null when none was sent (the service then asks for one). */
    static Long expected(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim())) {
            return null;
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new CatalogRuleViolation("If-Match must be a version, e.g. \"3\"", Map.of("If-Match", "Invalid"));
        }
    }
}
