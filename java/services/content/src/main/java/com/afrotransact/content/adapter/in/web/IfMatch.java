package com.afrotransact.content.adapter.in.web;

import java.util.Map;

import com.afrotransact.content.domain.ContentRuleViolation;

/** Optimistic locking over HTTP: {@code If-Match: "3"} names the version an editor loaded. */
final class IfMatch {

    private IfMatch() {
    }

    /** Parses {@code "3"}, {@code W/"3"} or {@code 3}; null, blank or {@code *} means "any version". */
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
            throw new ContentRuleViolation("content.invalid-if-match", "If-Match must be a version, e.g. \"3\"",
                    Map.of("If-Match", "Invalid"));
        }
    }
}
